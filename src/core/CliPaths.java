package core;

import java.io.File;

/**
 * GSL5 CLI fork：task 目录与 data.db 路径的<b>唯一</b>解析点。
 *
 * <p>背景（P1 bug：shell_list 能列出 shell 而 shell_exec 报「未找到 Shell」）：
 * wrapper（cli/gsl5）通过 chdir + {@code -Dgsl5.task}/{@code -Dgsl5.tasks.root}
 * 传递 task 语义，但 {@link Db} 静态块以相对路径 {@code "data.db"} 建连，
 * 解析基准是 JVM 的 user.dir；一旦 JVM 并非在 task 目录内启动（直接
 * {@code java -jar gsl5.jar cli ...} 调用、或调用方 cwd 漂移），不同子命令
 * 就会落到不同的 data.db。本类把解析逻辑收敛为一份，{@code Db} 与
 * {@code CliMain}（写锁、doctor、task list）全部经这里取路径。
 *
 * <p>taskDir 解析规则（按序命中）：
 * <ol>
 *   <li>非 CLI 模式（未置 {@code gsl5.cli=true}，即 GUI/MCP）→ user.dir，行为与上游一致；</li>
 *   <li>未显式配置 tasks-root（{@code -Dgsl5.tasks.root} 与 {@code GSL5_TASKS_ROOT} 均空）
 *       → user.dir（裸 {@code java -jar ... cli} 的兼容行为：就地建库）；</li>
 *   <li>task 名非法（空 / 含 {@code ..} / 含路径分隔符）→ user.dir（wrapper 已在入口
 *       拒绝非法名，这里是直接 java 调用的兜底，绝不把 DB 指到预期目录之外）；</li>
 *   <li>候选 {@code <tasks-root>/<task>} 与 user.dir 规范路径相同 → user.dir
 *       （wrapper 已 chdir 的常态，canonical 化消除符号链接与相对段差异）；</li>
 *   <li>tasks-root 为相对路径且 user.dir 目录名 == task → user.dir
 *       （兼容旧 wrapper：chdir 后仍把相对 root 原样传给 JVM，此时相对 root 已失效，
 *       若按新 cwd 相对解析会错套一层 {@code <task>/<root>/<task>}）；</li>
 *   <li>其余 → {@code <tasks-root>/<task>}（直接 java -jar 显式指定 root 的场景，
 *       保证任意启动 cwd 下所有子命令看到同一个 data.db）。</li>
 * </ol>
 *
 * <p>JDK 8，无第三方依赖；仅被静态初始化路径调用，不做任何 I/O（除 canonical 化）。
 */
public final class CliPaths {

    /** 缺省 task 名（与 CliMain/wrapper 的约定一致）。 */
    public static final String DEFAULT_TASK = "default";

    private CliPaths() {
    }

    /** 是否 CLI 模式（CliMain 入口在触碰 DB 前置 {@code gsl5.cli=true}）。 */
    public static boolean isCli() {
        return "true".equals(System.getProperty("gsl5.cli"));
    }

    /** 生效 task 名：{@code -Dgsl5.task} > {@code GSL5_TASK} 环境变量 > "default"。 */
    public static String taskName() {
        String prop = System.getProperty("gsl5.task");
        if (prop != null && !prop.trim().isEmpty()) return prop.trim();
        String env = System.getenv("GSL5_TASK");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        return DEFAULT_TASK;
    }

    /** 显式配置的 tasks-root：{@code -Dgsl5.tasks.root} > {@code GSL5_TASKS_ROOT}；未配置返回 null。 */
    public static String tasksRoot() {
        String prop = System.getProperty("gsl5.tasks.root");
        if (prop != null && !prop.trim().isEmpty()) return prop.trim();
        String env = System.getenv("GSL5_TASKS_ROOT");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        return null;
    }

    /** task 名合法性：非空、不含 {@code ..}、不含路径分隔符（与 wrapper 入口守卫一致）。 */
    public static boolean isValidTaskName(String name) {
        return name != null && !name.isEmpty()
                && !name.contains("..") && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /** 生效 task 目录（解析规则见类注释）。 */
    public static File taskDir() {
        File cwd = new File(System.getProperty("user.dir"));
        if (!isCli()) return cwd;
        String root = tasksRoot();
        String task = taskName();
        if (root == null || !isValidTaskName(task)) return cwd;
        File canonCwd = canonical(cwd);
        File canonCandidate = canonical(new File(root, task));
        if (canonCandidate.equals(canonCwd)) return cwd;
        // 旧 wrapper 兼容：相对 root 在 chdir 后已失效，cwd 目录名命中 task 时信任 cwd
        if (!new File(root).isAbsolute() && task.equals(canonCwd.getName())) return cwd;
        return canonCandidate;
    }

    /** 生效 task 的 data.db 文件。 */
    public static File dbFile() {
        return new File(taskDir(), "data.db");
    }

    /** SQLite JDBC URL（Windows 反斜杠归一为正斜杠）。 */
    public static String dbUrl() {
        return "jdbc:sqlite:" + dbFile().getAbsolutePath().replace('\\', '/');
    }

    /** 规范路径解析（符号链接/相对段归一），失败回落绝对路径。 */
    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (Exception e) {
            return f.getAbsoluteFile();
        }
    }
}
