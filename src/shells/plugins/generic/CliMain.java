package shells.plugins.generic;

import core.ApplicationContext;
import core.OperationAuditLog;
import core.OperationLogRuntime;
import core.persist.OperationLogStore;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GSL5 CLI entry: single capability surface for agents and humans (CLI fork).
 *
 * <p>Contract (docs/CLI化改造实施方案.md §2):
 * <ul>
 *   <li>stdout carries exactly one JSON envelope line per invocation; every log
 *       line (ours and stray ones from deep code) is diverted to stderr.</li>
 *   <li>Exit codes: 0 ok / 2 usage / 3 target failure / 4 timeout / 5 write-lock.</li>
 *   <li>Task isolation is done by the wrapper via chdir before the JVM starts;
 *       this class never touches the DB before that (see AGENTS.md discipline).</li>
 *   <li>Two layers: L1 `call &lt;tool&gt; --json` passthrough reaches all 50 built-in
 *       tools plus every @McpTool plugin tool; L2 flag sugar covers the hot set.</li>
 * </ul>
 *
 * <p>Encoding: Java 8, source UTF-8, Chinese strings written as unicode escapes so
 * the tool compiles under either platform default.
 */
public final class CliMain {

    private static final String DEFAULT_TASK = "default";
    /** Failure prefixes written into content[0].text by McpService.toolsCall/callPluginTool (F4). */
    private static final String[] FAILURE_PREFIXES = {
            "\u6267\u884c\u5931\u8d25",       // 执行失败
            "\u672a\u77e5\u5de5\u5177",       // 未知工具
            "\u7f3a\u5c11\u5fc5\u586b\u53c2\u6570", // 缺少必填参数
            "\u672a\u627e\u5230 Shell",       // 未找到 Shell
            "\u6dfb\u52a0\u5931\u8d25",       // 添加失败
            "\u7f16\u8f91\u5931\u8d25",       // 编辑失败
            "\u8fde\u63a5\u5931\u8d25",       // 连接失败
            "\u5bfc\u5165\u5931\u8d25",       // 导入失败
            "\u6709\u6548\u8f7d\u8377\u521d\u59cb\u5316\u5931\u8d25", // 有效载荷初始化失败
            "\u52a0\u5bc6\u5668\u521d\u59cb\u5316\u5931\u8d25", // 加密器初始化失败
            "\u63d2\u4ef6\u52a0\u8f7d\u5931\u8d25",   // 插件加载失败
    };
    /** \u5931\u8d25 = "failure"; a tool text containing this token within the first 30 chars is
     *  treated as failure even when its prefix is not enumerated above (covers upload/download failures). */
    private static final String FAILURE_TOKEN = "\u5931\u8d25";

    /** Protocol channel: the ONLY stream allowed to write to real stdout. */
    private static PrintStream proto;
    /** Resolved in run(): --task flag (or -Dgsl5.task from the wrapper) > GSL5_TASK env > "default". */
    private static String taskName = null;
    private static String tasksRoot = null;
    private static String format = "json";
    private static int timeoutSeconds = 120;
    private static McpService svc;

    private CliMain() {
    }

    // ============================================================ ENTRY

    public static int run(String[] args) {
        // CLI 模式标记必须先于任何 Db 类加载（CliPaths 据此锚定 tasks-root 解析，
        // 修复 list/exec 因子命令解析路径不一致落不同 data.db 的 P1 bug）
        System.setProperty("gsl5.cli", "true");
        try {
            proto = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (Exception e) {
            return 2;
        }
        try {
            // Stray System.out.println from deep code must never pollute stdout (§4.1).
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
        } catch (Exception ignored) {
            // stderr redirect is best-effort; proto capture above already isolates stdout.
        }
        String[] rest;
        try {
            rest = parseGlobalOptions(args);
        } catch (Exception e) {
            return fail(2, null, "bad global option: " + e.getMessage(), "see: gsl5 --help");
        }
        if (taskName == null) {
            // Priority: --task flag (or -Dgsl5.task set by the wrapper from --task) > GSL5_TASK env > default.
            String prop = System.getProperty("gsl5.task");
            String env = System.getenv("GSL5_TASK");
            taskName = (prop != null && !prop.trim().isEmpty()) ? prop.trim()
                    : (env != null && !env.trim().isEmpty() ? env.trim() : DEFAULT_TASK);
        }
        startTimeoutWatcher();
        try {
            // Audit persistence for headless runs (F5): init store + create session + bind.
            OperationLogRuntime.bootstrap(null);
        } catch (Throwable t) {
            System.err.println("[gsl5] audit bootstrap failed: " + t);
        }
        try {
            svc = new McpService(true);
        } catch (Throwable t) {
            return fail(3, null, "init failed: " + t, null);
        }
        try {
            return dispatch(rest);
        } catch (UsageException ue) {
            return fail(2, null, ue.getMessage(), "see: gsl5 --help");
        } catch (Throwable t) {
            return fail(3, null, t.getClass().getSimpleName() + ": " + t.getMessage(), null);
        }
    }

    // ============================================================ DISPATCH

    private static int dispatch(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0]) || "help".equals(args[0])) {
            usage();
            return 0;
        }
        String cmd = args[0];
        if (COMMANDS.containsKey(cmd)) {
            CmdSpec spec = COMMANDS.get(cmd);
            // 子命令 --help/-h：打印该命令用法（修复「flag --help requires a value」）
            for (int i = 1; i < args.length; i++) {
                if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                    usageFor(cmd, spec);
                    return 0;
                }
            }
            Map<String, Object> params = parseFlags(spec, args);
            return callTool(spec.tool, params);
        }
        String[] a = tail(args);
        if ("call".equals(cmd)) {
            if (a.length < 1) throw new UsageException("call requires <tool-name> [--json '<args>']");
            String tool = a[0];
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            for (int i = 1; i < a.length; i++) {
                if ("--json".equals(a[i])) {
                    if (i + 1 >= a.length) throw new UsageException("--json requires a value");
                    Object parsed = MiniJson.parse(a[++i]);
                    if (!(parsed instanceof Map)) throw new UsageException("--json must be a JSON object");
                    params.putAll((Map<String, Object>) parsed);
                } else if (a[i].startsWith("--") && a[i].contains("=")) {
                    int eq = a[i].indexOf('=');
                    params.put(a[i].substring(2, eq), a[i].substring(eq + 1));
                }
            }
            return callTool(tool, params);
        }
        if ("tools".equals(cmd)) return toolsCmd(a);
        if ("task".equals(cmd)) return taskCmd(a);
        if ("version".equals(cmd)) {
            Map<String, Object> m = okEnv();
            m.put("mode", "cli");
            m.put("version", ApplicationContext.RELEASE_TAG);
            m.put("java", System.getProperty("java.version"));
            m.put("os", System.getProperty("os.name"));
            out(m);
            return 0;
        }
        if ("doctor".equals(cmd)) return doctor();
        throw new UsageException("unknown command: " + cmd);
    }

    private static String[] tail(String[] args) {
        String[] a = new String[args.length - 1];
        System.arraycopy(args, 1, a, 0, a.length);
        return a;
    }

    // ============================================================ L2 TASK WRITE LOCK (v2 §4.4)

    /** Fast-write commands take an exclusive task lock (deterministic queuing, exit 5 on timeout). */
    private static final java.util.Set<String> WRITE_LOCKED = new java.util.HashSet<String>(
            java.util.Arrays.asList("shell_add", "shell_edit", "shell_delete", "shell_clone", "shell_import", "settings_set"));

    private static java.nio.channels.FileLock taskWriteLock;
    private static java.nio.channels.FileChannel taskWriteLockChannel;

    private static void acquireWriteLock(String tool) throws Exception {
        if (!WRITE_LOCKED.contains(tool)) return;
        java.nio.channels.FileChannel ch = new java.io.RandomAccessFile(".write.lock", "rw").getChannel();
        long deadline = System.currentTimeMillis() + 10_000L;
        java.nio.channels.FileLock lock = ch.tryLock();
        while (lock == null) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("task write lock busy >10s (another writer holds .write.lock)");
            }
            Thread.sleep(100L);
            lock = ch.tryLock();
        }
        taskWriteLock = lock;
        taskWriteLockChannel = ch;
    }

    private static void releaseWriteLock() {
        try {
            if (taskWriteLock != null) taskWriteLock.release();
        } catch (Throwable ignored) {
        }
        taskWriteLock = null;
        try {
            if (taskWriteLockChannel != null) taskWriteLockChannel.close();
        } catch (Throwable ignored) {
        }
        taskWriteLockChannel = null;
    }

    // ============================================================ CORE: TOOL CALL

    private static int callTool(String tool, Map<String, Object> params) {
        try {
            acquireWriteLock(tool);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", false);
            m.put("task", taskName);
            m.put("tool", tool);
            m.put("error", e.getMessage());
            m.put("hint", "retry with backoff, or split batch work across tasks");
            m.put("exitCode", 5);
            System.err.println("[gsl5] " + e.getMessage());
            out(m);
            return 5;
        }
        try {
            OperationAuditLog.ui("cli", tool, oneLine(params));
            String resp;
            try {
                resp = svc.cliCall(tool, params);
            } catch (Throwable t) {
                return fail(3, tool, "dispatch failed: " + t, null);
            }
            Object parsed = MiniJson.parse(resp);
            if (!(parsed instanceof Map)) {
                return fail(3, tool, "bad dispatcher response", null);
            }
            Map<String, Object> respMap = (Map<String, Object>) parsed;
            if (respMap.containsKey("error")) {
                Object err = respMap.get("error");
                String msg = (err instanceof Map) ? String.valueOf(((Map<String, Object>) err).get("message")) : String.valueOf(err);
                return fail(3, tool, msg, null);
            }
        String text = extractText(respMap);
        for (String prefix : FAILURE_PREFIXES) {
            if (text != null && text.startsWith(prefix)) {
                return fail(3, tool, text, null);
            }
        }
        if (text != null && text.indexOf(FAILURE_TOKEN) >= 0 && text.indexOf(FAILURE_TOKEN) < 30) {
            return fail(3, tool, text, null);
        }
        OperationAuditLog.ui("cli", tool + ".done", "ok");
        if ("text".equals(format)) {
            proto.println(text == null ? "" : text);
            proto.flush();
            return 0;
        }
        Map<String, Object> envelope = okEnv();
        envelope.put("tool", tool);
        Object asJson = (text == null) ? null : MiniJson.parse(text);
        if (asJson instanceof Map) {
            // merge tool payload into the envelope, but never let tool data clobber
            // the contract fields (ok/task/tool) -- reserved keys are dropped.
            for (Map.Entry<String, Object> e : ((Map<String, Object>) asJson).entrySet()) {
                String k = e.getKey();
                if ("ok".equals(k) || "task".equals(k) || "tool".equals(k)) continue;
                envelope.put(k, e.getValue());
            }
        } else {
            envelope.put("text", text == null ? "" : text);
        }
        out(envelope);
        return 0;
        } finally {
            releaseWriteLock();
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> respMap) {
        try {
            Map<String, Object> result = (Map<String, Object>) respMap.get("result");
            List<Object> content = (List<Object>) result.get("content");
            Map<String, Object> first = (Map<String, Object>) content.get(0);
            Object text = first.get("text");
            return text == null ? "" : String.valueOf(text);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String oneLine(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=');
            String v = String.valueOf(e.getValue());
            sb.append(v.length() > 200 ? v.substring(0, 200) + "..." : v);
        }
        return sb.toString();
    }

    // ============================================================ L2 COMMAND TABLE

    /** One parameter row: {flag, targetKey, required("R"|""), integer("I"|"")}; alias rows target another flag's key. */
    private static final class CmdSpec {
        final String tool;
        final String[][] params;

        CmdSpec(String tool, String[][] params) {
            this.tool = tool;
            this.params = params;
        }
    }

    private static final Map<String, CmdSpec> COMMANDS = new LinkedHashMap<String, CmdSpec>();

    private static void reg(String cmd, String tool, String[][] params) {
        COMMANDS.put(cmd, new CmdSpec(tool, params));
    }

    private static final String R = "R";
    private static final String I = "I";

    static {
        // ---- shell surface
        reg("shell_list", "shell_list", new String[][]{{"group", "group", "", ""}});
        reg("shell_search", "shell_search", new String[][]{{"keyword", "keyword", R, ""}});
        reg("shell_count", "shell_count", new String[][]{{"group", "group", "", ""}});
        reg("shell_get", "shell_get", new String[][]{{"shellId", "shellId", R, ""}});
        reg("shell_info", "shell_info", new String[][]{{"shellId", "shellId", R, ""}, {"encoding", "encoding", "", ""}});
        reg("shell_test", "shell_test", new String[][]{{"shellId", "shellId", R, ""}, {"encoding", "encoding", "", ""}});
        reg("shell_detect_encoding", "shell_detect_encoding", new String[][]{{"shellId", "shellId", R, ""}, {"persist", "persist", "", ""}});
        reg("shell_add", "shell_add", new String[][]{
                {"url", "url", R, ""}, {"password", "password", R, ""}, {"secretKey", "secretKey", R, ""},
                {"payload", "payload", R, ""}, {"cryption", "cryption", R, ""},
                {"encoding", "encoding", "", ""}, {"remark", "remark", "", ""}, {"headers", "headers", "", ""},
                {"proxyType", "proxyType", "", ""}, {"proxyHost", "proxyHost", "", ""}, {"proxyPort", "proxyPort", "", I},
                {"connTimeout", "connTimeout", "", I}, {"readTimeout", "readTimeout", "", I}, {"maxRetry", "maxRetry", "", I}});
        reg("shell_edit", "shell_edit", new String[][]{
                {"shellId", "shellId", R, ""},
                {"url", "url", "", ""}, {"password", "password", "", ""}, {"secretKey", "secretKey", "", ""},
                {"payload", "payload", "", ""}, {"cryption", "cryption", "", ""}, {"encoding", "encoding", "", ""},
                {"remark", "remark", "", ""}, {"headers", "headers", "", ""},
                {"proxyType", "proxyType", "", ""}, {"proxyHost", "proxyHost", "", ""}, {"proxyPort", "proxyPort", "", I},
                {"connTimeout", "connTimeout", "", I}, {"readTimeout", "readTimeout", "", I}, {"c2Profile", "c2Profile", "", ""}});
        reg("shell_delete", "shell_delete", new String[][]{{"shellId", "shellId", R, ""}});
        reg("shell_clone", "shell_clone", new String[][]{{"shellId", "shellId", R, ""}, {"newUrl", "newUrl", "", ""}});
        reg("shell_batch_test", "shell_batch_test", new String[][]{{"group", "group", "", ""}});
        reg("shell_exec", "shell_exec", new String[][]{
                {"shellId", "shellId", R, ""}, {"command", "command", R, ""},
                {"encoding", "encoding", "", ""}, {"os", "os", "", ""}});
        reg("shell_env", "shell_env", new String[][]{
                {"shellId", "shellId", R, ""}, {"action", "action", "", ""}, {"key", "key", "", ""}, {"value", "value", "", ""}});
        reg("shell_export", "shell_export", new String[][]{{"shellId", "shellId", "", ""}});
        reg("shell_import", "shell_import", new String[][]{{"link", "link", R, ""}});
        reg("shell_create", "shell_create", new String[][]{
                {"cryption", "cryption", R, ""}, {"genFile", "genFile", R, ""},
                {"url", "url", "", ""}, {"password", "password", "", ""}, {"secretKey", "secretKey", "", ""},
                {"payload", "payload", "", ""}, {"obfuscation", "obfuscation", "", ""}, {"c2Profile", "c2Profile", "", ""}});
        reg("shell_backup", "shell_backup", new String[][]{{"genFile", "genFile", "", ""}});
        // ---- file surface
        reg("file_list", "file_list", new String[][]{{"shellId", "shellId", R, ""}, {"path", "path", "", ""}, {"encoding", "encoding", "", ""}});
        reg("file_read", "file_read", new String[][]{{"shellId", "shellId", R, ""}, {"path", "path", R, ""}, {"encoding", "encoding", "", ""}});
        reg("file_upload_local", "file_upload_local", new String[][]{
                {"shellId", "shellId", R, ""}, {"file", "localPath", R, ""}, {"remotePath", "remotePath", R, ""}});
        reg("file_download_local", "file_download_local", new String[][]{
                {"shellId", "shellId", R, ""}, {"remotePath", "remotePath", R, ""}, {"out", "localPath", R, ""}});
        reg("file_delete", "file_delete", new String[][]{{"shellId", "shellId", R, ""}, {"path", "path", R, ""}});
        reg("file_mkdir", "file_mkdir", new String[][]{{"shellId", "shellId", R, ""}, {"path", "path", R, ""}});
        reg("file_copy", "file_copy", new String[][]{{"shellId", "shellId", R, ""}, {"src", "src", R, ""}, {"dest", "dest", R, ""}});
        reg("file_move", "file_move", new String[][]{{"shellId", "shellId", R, ""}, {"src", "src", R, ""}, {"dest", "dest", R, ""}});
        reg("file_search", "file_search", new String[][]{{"shellId", "shellId", R, ""}, {"pattern", "pattern", R, ""}, {"path", "path", "", ""}});
        reg("file_attr", "file_attr", new String[][]{
                {"shellId", "shellId", R, ""}, {"path", "path", R, ""}, {"type", "type", "", ""}, {"attr", "attr", "", ""}});
        reg("file_roots", "file_roots", new String[][]{{"shellId", "shellId", R, ""}});
        reg("file_remote_down", "file_remote_down", new String[][]{
                {"shellId", "shellId", R, ""}, {"url", "url", R, ""}, {"savePath", "savePath", R, ""}});
        // ---- db surface
        reg("db_connect", "db_connect", new String[][]{
                {"shellId", "shellId", R, ""}, {"target", "target", "", ""},
                {"dbHost", "dbHost", "", ""}, {"dbPort", "dbPort", "", ""}, {"dbUser", "dbUser", "", ""},
                {"dbPass", "dbPass", "", ""}, {"dbType", "dbType", "", ""}, {"dbName", "dbName", "", ""},
                {"configName", "configName", "", ""}});
        reg("db_exec", "db_exec", new String[][]{
                {"shellId", "shellId", R, ""}, {"sql", "sql", R, ""},
                {"configName", "configName", "", ""}, {"dbName", "dbName", "", ""}, {"execType", "execType", "", ""}});
        reg("db_tables", "db_tables", new String[][]{
                {"shellId", "shellId", R, ""}, {"database", "database", R, ""}, {"configName", "configName", "", ""}});
        reg("db_list_types", "db_list_types", new String[][]{{"shellId", "shellId", R, ""}});
        // ---- misc surface
        reg("process_list", "process_list", new String[][]{{"shellId", "shellId", R, ""}});
        reg("net_info", "net_info", new String[][]{{"shellId", "shellId", R, ""}});
        reg("payload_list", "payload_list", new String[][]{});
        reg("c2profile_list", "c2profile_list", new String[][]{{"payload", "payload", "", ""}});
        reg("c2profile_get", "c2profile_get", new String[][]{{"name", "name", R, ""}});
        reg("settings_get", "settings_get", new String[][]{{"key", "key", "", ""}});
        reg("settings_set", "settings_set", new String[][]{{"key", "key", R, ""}, {"value", "value", R, ""}});
        reg("oplog_query", "oplog_query", new String[][]{{"limit", "limit", "", I}, {"action", "action", "", ""}});
    }

    private static Map<String, Object> parseFlags(CmdSpec spec, String[] args) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) throw new UsageException("unexpected argument: " + a + " (flags must be --name value)");
            String flag;
            String value;
            int eq = a.indexOf('=');
            if (eq > 0) {
                flag = a.substring(2, eq);
                value = a.substring(eq + 1);
            } else {
                flag = a.substring(2);
                if (i + 1 >= args.length) throw new UsageException("flag --" + flag + " requires a value");
                value = args[++i];
            }
            String[] row = specRow(spec, flag);
            if (row == null) throw new UsageException("unknown flag --" + flag + " for " + spec.tool);
            Object v = "I".equals(row[3]) ? toInt(value) : (Object) value;
            params.put(row[1], v);
        }
        for (String[] row : spec.params) {
            if (R.equals(row[2]) && !params.containsKey(row[1])) {
                throw new UsageException("missing required flag --" + row[0] + " for " + spec.tool);
            }
        }
        return params;
    }

    private static String[] specRow(CmdSpec spec, String flag) {
        for (String[] row : spec.params) {
            if (row[0].equals(flag)) return row;
        }
        return null;
    }

    private static Integer toInt(String v) {
        try {
            return Integer.valueOf(v.trim());
        } catch (Exception e) {
            throw new UsageException("expected integer, got: " + v);
        }
    }

    // ============================================================ META: tools

    private static int toolsCmd(String[] a) {
        boolean json = false;
        String keyword = null;
        String single = null;
        for (String s : a) {
            if ("--json".equals(s)) json = true;
            else if (single == null && keyword == null) single = s;
            else if (keyword == null) keyword = s;
        }
        String raw = svc.cliToolsListRaw();
        Object parsed = MiniJson.parse(raw);
        List<Object> tools;
        try {
            tools = (List<Object>) ((Map<String, Object>) ((Map<String, Object>) parsed).get("result")).get("tools");
        } catch (Throwable t) {
            return fail(3, "tools", "cannot parse tools/list", null);
        }
        List<Object> selected = new ArrayList<Object>();
        for (Object o : tools) {
            Map<String, Object> t = (Map<String, Object>) o;
            String name = String.valueOf(t.get("name"));
            String desc = String.valueOf(t.get("description"));
            if (single != null) {
                if (name.equals(single)) {
                    selected.add(t);
                    break;
                }
                continue;
            }
            if (keyword == null || name.contains(keyword) || desc.contains(keyword)) {
                selected.add(t);
            }
        }
        if (single != null && selected.isEmpty()) {
            // exact-name miss falls back to keyword search (discovery, v2 doc section 2.4)
            for (Object o : tools) {
                Map<String, Object> t = (Map<String, Object>) o;
                String name = String.valueOf(t.get("name"));
                String desc = String.valueOf(t.get("description"));
                if (name.contains(single) || desc.contains(single)) selected.add(t);
            }
            if (selected.isEmpty()) {
                return fail(2, "tools", "unknown tool or keyword: " + single, "try: gsl5 tools (list all)");
            }
            single = null; // render as catalog lines, not single-tool schema detail
        }
        if (json) {
            Map<String, Object> m = okEnv();
            m.put("count", selected.size());
            m.put("tools", selected);
            out(m);
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : selected) {
            Map<String, Object> t = (Map<String, Object>) o;
            sb.append(t.get("name")).append("  ").append(t.get("description")).append('\n');
            if (single != null) {
                Map<String, Object> schema = (Map<String, Object>) t.get("inputSchema");
                if (schema != null) {
                    Map<String, Object> props = (Map<String, Object>) schema.get("properties");
                    List<Object> required = (List<Object>) schema.get("required");
                    if (props != null) {
                        for (Object po : props.entrySet()) {
                            Map.Entry<String, Object> e = (Map.Entry<String, Object>) po;
                            boolean req = required != null && required.contains(e.getKey());
                            String d = "";
                            if (e.getValue() instanceof Map) {
                                Object dObj = ((Map<String, Object>) e.getValue()).get("description");
                                d = dObj == null ? "" : String.valueOf(dObj);
                            }
                            sb.append("    --").append(e.getKey()).append(req ? " (required)" : "")
                                    .append(d.isEmpty() ? "" : "  " + d).append('\n');
                        }
                    }
                }
            }
        }
        if (single == null) {
            sb.append("\n# ").append(selected.size()).append(" tool(s). details: gsl5 tools <tool-name> | call: gsl5 call <name> --json '{...}'\n");
        }
        proto.print(sb);
        proto.flush();
        return 0;
    }

    // ============================================================ META: task

    private static File tasksRootDir() {
        String root = tasksRoot;
        if (root == null || root.trim().isEmpty()) root = System.getProperty("gsl5.tasks.root");
        if (root == null || root.trim().isEmpty()) {
            String env = System.getenv("GSL5_TASKS_ROOT");
            if (env != null && !env.trim().isEmpty()) root = env.trim();
        }
        File cwd = new File(System.getProperty("user.dir"));
        return new File((root == null || root.trim().isEmpty()) ? cwd.getParent() : root);
    }

    private static int taskCmd(String[] a) {
        String sub = a.length > 0 ? a[0] : "list";
        if ("path".equals(sub)) {
            if ("text".equals(format)) {
                proto.println(new File(System.getProperty("user.dir")).getAbsolutePath());
            } else {
                Map<String, Object> m = okEnv();
                m.put("task", taskName);
                m.put("path", new File(System.getProperty("user.dir")).getAbsolutePath());
                out(m);
            }
            return 0;
        }
        if (!"list".equals(sub)) throw new UsageException("task: unknown sub-command: " + sub + " (list|path)");
        File root = tasksRootDir();
        List<String> names = new ArrayList<String>();
        File[] dirs = root.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && !"shared".equals(d.getName())) names.add(d.getName());
            }
        }
        Collections.sort(names);
        if (!names.contains(DEFAULT_TASK)) names.add(0, DEFAULT_TASK);
        if ("text".equals(format)) {
            StringBuilder sb = new StringBuilder();
            for (String n : names) {
                sb.append(n);
                if (n.equals(taskName)) sb.append("  <- current");
                if (DEFAULT_TASK.equals(n)) sb.append("  [default]");
                sb.append('\n');
            }
            proto.print(sb);
            proto.flush();
        } else {
            Map<String, Object> m = okEnv();
            m.put("tasksRoot", root.getAbsolutePath());
            m.put("current", taskName);
            m.put("tasks", names);
            out(m);
        }
        return 0;
    }

    // ============================================================ META: doctor

    private static int doctor() {
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        Map<String, Object> checks = new LinkedHashMap<String, Object>();

        String javaVersion = System.getProperty("java.version");
        checks.put("java", javaVersion);
        if (!javaVersion.startsWith("1.8")) warnings.add("built for JDK8 baseline, running on " + javaVersion);

        checks.put("version", ApplicationContext.RELEASE_TAG);

        try {
            File cwd = new File(System.getProperty("user.dir"));
            String cwdCanon = cwd.getCanonicalPath();
            checks.put("taskPath", cwdCanon);
            File db = new File(cwd, "data.db");
            checks.put("dataDb", db.isFile() ? "present" : "absent (created on first use)");
            if (Files.isSymbolicLink(Paths.get(cwd.getPath(), "data.db"))) errors.add("data.db must not be a symlink");
            File defaultDir = new File(cwd.getParentFile(), DEFAULT_TASK);
            if (!DEFAULT_TASK.equals(taskName) && cwdCanon.equals(defaultDir.getCanonicalPath())) {
                errors.add("task directory collides with 'default'");
            }
            FileStore store = Files.getFileStore(cwd.toPath());
            String fsType = store.type();
            checks.put("filesystem", fsType);
            String lower = fsType == null ? "" : fsType.toLowerCase();
            if (lower.contains("nfs") || lower.contains("cifs") || lower.contains("smb")) {
                errors.add("task directory on network filesystem (WAL unsafe): " + fsType);
            }
        } catch (Throwable t) {
            warnings.add("task dir check failed: " + t);
        }

        checks.put("auditStore", OperationLogStore.isAvailable() ? "available" : "unavailable");
        if (!OperationLogStore.isAvailable()) warnings.add("audit persistence unavailable (entries kept in memory)");

        try {
            String raw = svc.cliToolsListRaw();
            Object parsed = MiniJson.parse(raw);
            List<Object> tools = (List<Object>) ((Map<String, Object>) ((Map<String, Object>) parsed).get("result")).get("tools");
            List<String> names = new ArrayList<String>();
            for (Object o : tools) names.add(String.valueOf(((Map<String, Object>) o).get("name")));
            List<String> missing = new ArrayList<String>();
            for (String cmd : COMMANDS.keySet()) {
                if (!names.contains(cmd)) missing.add(cmd);
            }
            checks.put("toolCatalog", names.size());
            checks.put("l2Commands", COMMANDS.size());
            if (!missing.isEmpty()) {
                errors.add("L2 commands missing from tools/list (upstream rename?): " + missing);
            } else {
                checks.put("l2Coverage", "ok");
            }
        } catch (Throwable t) {
            warnings.add("tools/list coverage check failed: " + t);
        }

        File token = new File("profile/mcp.token");
        checks.put("mcpToken", token.isFile() ? "present" : "absent");

        Map<String, Object> m = okEnv();
        m.put("mode", "doctor");
        m.put("checks", checks);
        m.put("warnings", warnings);
        m.put("errors", errors);
        out(m);
        return errors.isEmpty() ? 0 : 3;
    }

    // ============================================================ GLOBAL OPTIONS / TIMEOUT / AUDIT ENVELOPE

    private static String[] parseGlobalOptions(String[] args) {
        List<String> rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--task".equals(a)) {
                taskName = valueOf(args, ++i, "--task");
            } else if (a.startsWith("--task=")) {
                taskName = a.substring("--task=".length());
            } else if ("--tasks-root".equals(a)) {
                tasksRoot = valueOf(args, ++i, "--tasks-root");
            } else if (a.startsWith("--tasks-root=")) {
                tasksRoot = a.substring("--tasks-root=".length());
            } else if ("--format".equals(a)) {
                format = valueOf(args, ++i, "--format");
            } else if (a.startsWith("--format=")) {
                format = a.substring("--format=".length());
            } else if ("--timeout".equals(a)) {
                timeoutSeconds = Integer.parseInt(valueOf(args, ++i, "--timeout").trim());
            } else if (a.startsWith("--timeout=")) {
                timeoutSeconds = Integer.parseInt(a.substring("--timeout=".length()).trim());
            } else if ("--no-color".equals(a)) {
                // text-mode coloring is not applied in v1; accepted for compatibility
            } else {
                rest.add(a);
            }
        }
        if (!"json".equals(format) && !"text".equals(format)) {
            throw new UsageException("--format must be json|text");
        }
        return rest.toArray(new String[0]);
    }

    private static String valueOf(String[] args, int idx, String flag) {
        if (idx >= args.length) throw new UsageException(flag + " requires a value");
        return args[idx];
    }

    private static void startTimeoutWatcher() {
        if (timeoutSeconds <= 0) return;
        final long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        Thread watcher = new Thread(new Runnable() {
            @Override
            public void run() {
                long remain;
                while ((remain = deadline - System.currentTimeMillis()) > 0) {
                    try {
                        Thread.sleep(Math.min(remain, 1000L));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("ok", false);
                m.put("task", taskName);
                m.put("error", "timeout after " + timeoutSeconds + "s");
                m.put("exitCode", 4);
                try {
                    proto.println(MiniJson.write(m));
                    proto.flush();
                } catch (Throwable ignored) {
                }
                Runtime.getRuntime().halt(4);
            }
        }, "gsl5-cli-timeout");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static Map<String, Object> okEnv() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("task", taskName);
        return m;
    }

    private static void out(Map<String, Object> m) {
        proto.println(MiniJson.write(m));
        proto.flush();
    }

    private static int fail(int code, String tool, String error, String hint) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("task", taskName);
        if (tool != null) m.put("tool", tool);
        m.put("error", error == null ? "" : error);
        if (hint != null) m.put("hint", hint);
        m.put("exitCode", code);
        System.err.println("[gsl5] error: " + error);
        out(m);
        return code;
    }

    private static void usage() {
        StringBuilder sb = new StringBuilder();
        sb.append("gsl5 - WebShell management CLI (GSL5 ").append(ApplicationContext.RELEASE_TAG).append(")\n\n");
        sb.append("usage: gsl5 [--task NAME] [--tasks-root DIR] [--format json|text] [--timeout SEC] <command> [flags]\n");
        sb.append("  task defaults to 'default'; one task = one isolated data.db (wrapper chdir).\n\n");
        sb.append("commands:\n");
        for (Map.Entry<String, CmdSpec> e : COMMANDS.entrySet()) {
            sb.append("  ").append(String.format("%-22s", e.getKey()));
            StringBuilder flags = new StringBuilder();
            for (String[] row : e.getValue().params) {
                flags.append(" --").append(row[0]);
                if (R.equals(row[2])) flags.append('*');
            }
            String f = flags.toString();
            sb.append(f.length() > 74 ? f.substring(0, 74) + " ..." : f).append("  (").append(e.getValue().tool).append(")\n");
        }
        sb.append("  call <tool> --json     # L1 passthrough: any tools/list tool incl. plugin tools\n");
        sb.append("  tools [keyword]        # tool catalog; tools <name> shows full flags\n");
        sb.append("  task list|path         # task management (default task: default)\n");
        sb.append("  version | doctor       # build info / self-check\n");
        sb.append("  --flags marked * are required. exit codes: 0 ok / 2 usage / 3 target / 4 timeout / 5 lock\n");
        proto.print(sb);
        proto.flush();
    }

    /** 子命令级用法（gsl5 <cmd> --help）：列出该命令全部 flag，* 为必填。 */
    private static void usageFor(String cmd, CmdSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("usage: gsl5 [--task NAME] [--tasks-root DIR] ").append(cmd);
        for (String[] row : spec.params) {
            sb.append(" --").append(row[0]);
            if (R.equals(row[2])) sb.append('*');
        }
        sb.append("\n  tool: ").append(spec.tool).append("（完整参数说明：gsl5 tools ").append(spec.tool).append("）\n");
        proto.print(sb);
        proto.flush();
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    // ============================================================ MiniJson (F2: no JSON parser exists in the tree)

    /** Minimal recursive-descent JSON parser/writer (JDK8, zero dependencies). */
    static final class MiniJson {

        private MiniJson() {
        }

        static Object parse(String s) {
            try {
                P p = new P(s);
                p.ws();
                Object v = p.value();
                p.ws();
                if (p.i < p.n) return null; // trailing garbage -> treat as parse failure
                return v;
            } catch (Throwable t) {
                return null;
            }
        }

        private static final class P {
            final String s;
            final int n;
            int i;

            P(String s) {
                this.s = s;
                this.n = s == null ? 0 : s.length();
            }

            void ws() {
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                    else break;
                }
            }

            Object value() {
                if (i >= n) throw new IllegalStateException("eof");
                char c = s.charAt(i);
                if (c == '{') return obj();
                if (c == '[') return arr();
                if (c == '"') return str();
                if (c == 't') {
                    expect("true");
                    return Boolean.TRUE;
                }
                if (c == 'f') {
                    expect("false");
                    return Boolean.FALSE;
                }
                if (c == 'n') {
                    expect("null");
                    return null;
                }
                return num();
            }

            void expect(String lit) {
                if (!s.startsWith(lit, i)) throw new IllegalStateException("bad literal at " + i);
                i += lit.length();
            }

            Map<String, Object> obj() {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                i++; // {
                ws();
                if (i < n && s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    if (s.charAt(i) != ':') throw new IllegalStateException("expected :");
                    i++;
                    ws();
                    m.put(k, value());
                    ws();
                    char c = s.charAt(i);
                    i++;
                    if (c == '}') return m;
                    if (c != ',') throw new IllegalStateException("expected , or }");
                }
            }

            List<Object> arr() {
                List<Object> l = new ArrayList<Object>();
                i++; // [
                ws();
                if (i < n && s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    ws();
                    l.add(value());
                    ws();
                    char c = s.charAt(i);
                    i++;
                    if (c == ']') return l;
                    if (c != ',') throw new IllegalStateException("expected , or ]");
                }
            }

            String str() {
                if (s.charAt(i) != '"') throw new IllegalStateException("expected string");
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    char c = s.charAt(i++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"': sb.append('"'); break;
                            case '\\': sb.append('\\'); break;
                            case '/': sb.append('/'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'u':
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                                break;
                            default: throw new IllegalStateException("bad escape \\" + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new IllegalStateException("unterminated string");
            }

            Object num() {
                int start = i;
                while (i < n) {
                    char c = s.charAt(i);
                    if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                    else break;
                }
                String t = s.substring(start, i);
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                    try {
                        return Long.valueOf(t);
                    } catch (NumberFormatException nfe) {
                        return Double.valueOf(t);
                    }
                }
                return Double.valueOf(t);
            }
        }

        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            writeValue(sb, o);
            return sb.toString();
        }

        private static void writeValue(StringBuilder sb, Object o) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof String) {
                writeString(sb, (String) o);
            } else if (o instanceof Boolean || o instanceof Number) {
                sb.append(o.toString());
            } else if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    writeValue(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof Iterable) {
                sb.append('[');
                boolean first = true;
                for (Object v : (Iterable<?>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    writeValue(sb, v);
                }
                sb.append(']');
            } else {
                writeString(sb, String.valueOf(o));
            }
        }

        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
