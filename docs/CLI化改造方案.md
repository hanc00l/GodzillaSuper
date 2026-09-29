# GSL5 CLI 化改造方案（含 project 隔离 / skills / 插件双形态）

> 决策基线：放弃 MCP 集成路线（stdio 与 SSE 均不再作为 agent 接入方式）；GSL5 收敛为 **CLI 单一能力源**；
> 在 CLI 之上派生两种消费形态——**通用 skill**（任意 agent 可用）与**插件适配**（替换 deepstrike/mirrorstrike 现有 godzilla-cli 集成）；
> CLI 增加 **project 参数**，解决多项目、多进程并发隔离。
> 前置分析见 [MCP-改造分析-stdio与CLI.md](MCP-改造分析-stdio与CLI.md)。
>
> **术语修订**：本文的 `project` 概念统一更名为 **task**——CLI 参数为可选 `--task`（**缺省使用默认 task `default`**）、`--projects-root`→`--tasks-root`、`GSL5_PROJECT(S_ROOT)`→`GSL5_TASK(S_ROOT)`、`project list|path`→`task list|path`、目录布局 `<projects-root>/<project>/`→`<tasks-root>/<task>/`；「一 project 一 DB」相应为「一 task 一 DB」。实施规格以 [CLI化改造实施方案.md](CLI化改造实施方案.md) 为准。

---

## 0. 范围声明

- **取消**：原报告 P0（stdio MCP）、P0.5（`GSL5_MCP_TOOLS` 白名单）、P2（Streamable HTTP 升级）。
- **保留不动**：McpService 的 SSE 服务代码原样保留（人工用 Claude 调试的旁路，不投入新开发）。
- **吸收**：原 P1（CLI 透传）升级为本方案核心；`toolsCall()` 的 50 个实现 + 10 个 `@McpTool` 插件工具经透传全部可达。
- **语义决策（修订）**：CLI 词汇**不兼容旧 godzilla-cli**，直接采用 **GSL5 原生工具名**（`shell_list`/`shell_exec`/`file_read`…，与 MCP tools/list 同名同参）——上游语义即 CLI 语义，零翻译层；deepstrike/mirrorstrike 侧**作相应改造**（D3/D4）。
- **新增**：project 隔离、退出码契约、skill 手册、两侧适配器改造、并发治理（§3.6 分层方案）。

---

## 1. 架构总览

```text
                         ┌────────────────────────────────────────┐
                         │  gsl5.jar（GSL5 3.1.8 + CLI 增量）      │
                         │                                        │
                         │  Gsl5Main ──"cli"──▶ CliMain（新增）    │
                         │                        │               │
                         │     L1 透传: call <tool> --json         │
                         │        └─▶ McpService.dispatch(        │
                         │             "tools/call", params, id)  │
                         │     L2 语义子命令（§3.3 词汇表）          │
                         │     --project 隔离（§3.5）              │
                         │     WAL + busy_timeout（§3.6）          │
                         └───────────────┬────────────────────────┘
                                         │ java -jar（bash wrapper: gsl5）
              ┌──────────────────────────┼──────────────────────────┐
              ▼                          ▼                          ▼
   ┌─────────────────────┐   ┌─────────────────────┐   ┌──────────────────────────┐
   │ 形态 A：通用 skill    │   │ 形态 B：mirrorstrike │   │ 形态 C：deepstrike 插件    │
   │ /opt/tools/gsl5/     │   │ skills/pentest-gsl5  │   │ packages/webshell/cli.ts │
   │ SKILL.md（工具手册）  │   │ （替换 pentest-godzilla│   │ GodzillaCli → Gsl5Cli    │
   │ 任意 agent 可引用     │   │  编排协议保留）        │   │ （dsh 插件 + ms 适配器共用）│
   └─────────────────────┘   └─────────────────────┘   └──────────────────────────┘
```

**单一事实源原则**：所有能力只实现在 CLI 一处；skill 教"何时用、怎么编排"，插件做"强类型精选"。MCP 的 tools/list、stdio、SSE 不再进入 agent 数据面。

---

## 2. 已核实的现状约束（方案前提）

| # | 事实 | 来源 | 对方案的影响 |
|---|---|---|---|
| C1 | `Db` 硬编码 `jdbc:sqlite:data.db`（CWD 相对，2 处）；JVM 内 `synchronized`，跨进程无显式 `busy_timeout`/WAL | `core/Db.java:32,660` | project 隔离主机制选 **wrapper chdir**；并发加固改 Db |
| C2 | CWD 相对资源共 3 类：`data.db`、`GodzillaCache/<shellId>`（Db.java:240）、`profile/`（ApplicationContext 多处） | grep 已枚举 | chdir 后 data.db/GodzillaCache 自动落 project；`profile/` 用共享符号链接 |
| C3 | `McpService(boolean skipUi)` 构造器无 Swing；`dispatch/toolsCall` 为 private instance——CLI 入口放进同文件即可直调 | `McpService.java:98` | 复用路径成立，无需反射 |
| C4 | 审计日志进程内 `ArrayDeque`，仅 `OperationLogStore` 可用且绑定 sessionId 才落库 | `OperationAuditLog.java` | CLI 需绑定固定 sessionId 才能留下审计 |
| C5 | 仓库无 `lib/`、`bin/` 构建依赖；本机无 gsl5.jar；JDK 8/11/21/25 可用 | 文件系统核实 | S0 设"获取 jar"门（§6-S0 双路径） |
| C6 | 旧 godzilla-cli.jar 词汇 8 个：`generate-shell/shell-add/shell-delete/command-exec/basic-info/file-read/file-write/shell-list`；输出契约 `{ok, error?, shellId?, output?, info?, content?, filename?}` | `deepstrike/webshell/cli.ts` 实测记录 | 作为 **D3/D4 侧改造的迁移对照基线**；CLI 本身采用 GSL5 原生词汇（§0 语义决策），不做兼容层 |
| C7 | 源码默认 GBK，`McpService.java` 为 UTF-8；改 GBK 文件须 ASCII-only 或 sed | README 构建节 | 新文件 UTF-8；改 Db/Gsl5Main 用 ASCII 补丁 |
| C8 | mirrorstrike skill 范式：frontmatter `name/description/whenToUse/priority/alwaysApply`；`{BASE}/SKILL.md` 工具手册分离模式 | `/opt/mirrorstrike/skills/*` | 通用 skill 直接套用该范式 |

---

## 3. CLI 设计规格

### 3.1 启动语法

```bash
gsl5 [全局选项] <command> [命令参数]
# 等价：java -jar gsl5.jar cli [全局选项] <command> ...（wrapper 见 §3.7）

全局选项：
  --project NAME          项目名，默认 "default"
  --projects-root DIR     项目根目录；默认 $GSL5_PROJECTS_ROOT，再默认 ./projects
  --format json|text      输出格式，默认 json（text 供人工调试）
  --timeout SECONDS       单命令超时，默认 120
  --no-color              text 模式禁用着色
```

解析优先级：`--project` > `GSL5_PROJECT` > `default`；`--projects-root` > `GSL5_PROJECTS_ROOT` > `<cwd>/projects`。

### 3.2 输出契约（强约束）

- **stdout 恰好一行 JSON**：成功 `{"ok":true, ...业务字段}`；失败 `{"ok":false,"error":"...","hint":"...","exitCode":3}`。
  日志一律 stderr（`[gsl5]` 前缀）——skill 与适配器都只解析 stdout。
- **退出码**：`0` 成功 / `2` 用法错误 / `3` 目标连接或执行失败 / `4` 超时 / `5` 锁竞争重试耗尽。
- **大文件纪律**：`file-read --out <本地路径>` 直存不经上下文；`file-write --file <本地>` 直传；JSON 内的 base64 仅限小内容（沿用旧契约，skill 中写明阈值）。

### 3.3 命令词汇：与 GSL5 原生语义对齐（不做旧 jar 兼容层）

**设计规则**：CLI 命令名 = MCP 工具名（`toolsCall` 的 switch 名），命令参数 = 该工具 JSON Schema 的参数名（原样作长 flag），返回内容 = 工具实现产出的业务字段（外包 `ok` 信封）。一条机械映射规则覆盖全部 **50 内置 + 10 插件工具**，上游新增/改名工具经 `call` 透传或重编译后自动跟上——CLI 词汇与上游**零漂移**。

```bash
# 命令 = 工具名；参数 = schema 参数名
gsl5 --project taskA shell_list
gsl5 --project taskA shell_add --url http://t/shell.jsp --password pass --secretKey key \
     --payload JavaDynamicPayload --cryption JAVA_AES_BASE64
gsl5 --project taskA shell_exec --shellId 3 --command 'id && uname -a' --encoding auto
gsl5 --project taskA file_read --shellId 3 --path /etc/passwd --out ./passwd.txt   # --out 直存本地
gsl5 --project taskA shell_create --cryption aesbase64 --genFile ./shell.jsp --obfuscation superUnicode
gsl5 --project taskA db_exec --shellId 3 --sql 'select @@version' --execType select
gsl5 --project taskA plugin_call plugin_TH_TOOLS_exec --args '{"shellId":"3","tool":"GodPotato"}'

# 元命令（不属于工具面）
gsl5 --project taskA call <tool-name> --json '{"..."}'   # JSON 透传兜底（复杂/嵌套参数）
gsl5 project list | path    # 项目管理
gsl5 version | doctor       # 自检
```

映射实现：启动时经 seam 调 `toolsList()` 拿到 schema，与内置命令表（含少量 flag 别名，如 `--out`）合成参数解析器——**命令表不做第二份业务描述**，schema 即文档。

> 旧 jar 词汇（C6）的迁移映射由消费侧负责：deepstrike 在 `Gsl5Cli` 适配器内做 `commandExec(shellId, cmd) → shell_exec --shellId --command` 的方法级映射；mirrorstrike 在 skill 的「常用命令」段直接教新词汇。两侧都是一次性别改，不进 CLI。

### 3.4 L1 透传实现（能力 100% 可达的关键）

```java
// McpService.java 内新增（同类可访问 private dispatch/toolsCall）
public static int cliMain(String[] args) {
    GOptionPane.SUPPRESS_UI = true;                    // 复用 mcp 模式免弹窗
    // ... 解析全局选项；project 由 wrapper chdir 保证（§3.5）
    McpService svc = new McpService(true);
    Map<String,Object> params = m("name", toolName, "arguments", jsonArgs);
    String result = svc.toolsCall(params, 1);          // 直接复用 50+10 工具分发
    // 提取 content[0].text → 组装 {ok:true, ...}（失败解析按退出码 3）
}
```

L2 语义命令 = L1 透传的参数映射糖衣（一个 `Map<String,CommandSpec>` 表驱动），无第二套业务逻辑。

### 3.5 project 隔离设计

**主机制：wrapper chdir（零 Java 路径改动）**。Java 无法 `chdir`，而 C1 表明全部状态资源都是 CWD 相对——因此 wrapper 把 chdir 作为 project 语义的执行点：

```bash
#!/bin/bash
# /opt/tools/gsl5/gsl5 —— 真实入口
JAR=/opt/tools/gsl5/gsl5.jar
ROOT="${GSL5_PROJECTS_ROOT:-$PWD/projects}"
# 提取 --project / --projects-root（透传给 java 前 slice 掉），解析出 PROJECT_DIR
mkdir -p "$PROJECT_DIR"
cd "$PROJECT_DIR" || exit 2
# project 初始化：profile/ 共享（C2 profile 是全局模板，不随项目复制）
[ -e profile ] || ln -s ../../shared/profile profile   # 相对符号链接到共享根
mkdir -p GodzillaCache logs
exec java ${GSL5_JAVA_OPTS:--Djava.awt.headless=true} -jar "$JAR" cli "${ARGS[@]}"
```

**project 目录布局**：

```text
<projects-root>/
├── shared/profile/            # C2 模板等全局资产（文件级共享，非 DB；各 project 符号链接）
└── <project>/
    ├── data.db                # 本 project 唯一 DB：shell/分组/设置/插件登记
    ├── GodzillaCache/<shellId>/   # 已按 shellId 天然隔离（C2）
    ├── logs/
    │   ├── gsl5.log           # stderr 落盘副本（可选）
    │   └── operation_audit.db # 审计库随 project 内聚（不共享）
    └── profile -> ../shared/profile
```

**硬约束：一 project 一 DB，DB 不跨 project 共享。** 落实为三条机制：

1. **唯一性守卫**：CLI 初始化 project 时对 `<project>/data.db` 做 `realpath` 校验——拒绝符号链接、拒绝两个项目名解析到同一物理目录（防止配置漂移造成的隐性共享）；`doctor` 子命令复检。
2. **审计库随项目内聚**：`OperationLogStore` 数据目录支持 `-Dgsl5.data.dir`（wrapper 传入 `<project>/logs`），审计从全局 `~/.gsl5/operation_audit.db` 改为每 project 独立——任务审计随任务边界走，归档/清理 project 即审计随之生命周期化；顺带消除了原全局审计库的跨项目写竞争点。如需全局视图，在项目归档时汇聚各 `operation_audit.db`。
3. **跨 project 协作只走显式通道**：`shell_export`/`shell_import`（`gsl5://` 链接，单条/批量）或离线复制整个 project 目录；不存在任何隐式共享路径。

映射约定不变：**一个任务（task/engagement/session）= 一个 project = 一个唯一 DB**。

### 3.6 多进程并发治理（同 project）——"能否解决"：能，分层确定性方案

前置事实（决定并发形态的两个机制）：① 各 `Db` 方法是 JVM 内 `synchronized` 的**单条自动提交语句**（无跨语句长事务），锁持有时间为毫秒级；② 长耗时操作（`shell_exec`/`file_*`/Mimikatz）持有的是**到目标的网络连接**，全程不持有 SQLite 锁。即：写竞争窗口极小且与命令时长无关——这是"可解"的根本前提。

| 层 | 机制 | 解决什么 |
|---|---|---|
| L1 存储层 | `PRAGMA journal_mode=WAL` + `busy_timeout=5000` + `synchronous=NORMAL`（Db 连接后一次性执行） | 读写不互斥（WAL）；偶发写碰撞从"立即报 BUSY"变为"自动等待重试"。毫秒级写事务 × 5s 预算 ≈ 可吸收上千次排队写 |
| L2 应用层 | **project 级写锁**（Java `FileChannel.lock` 于 `<project>/.write.lock`）：仅对**快速写命令**（`shell_add/delete/edit/clone/import`、`settings_set`、`project` 管理）加独占锁，锁等待上限 10s，超时退出码 5 | 把高频小写从"概率性避让"升级为**确定性排队**——同 project 写命令完全串行化，杜绝 BUSY |
| L3 长操作 | `shell_exec`/`file_*`/`db_exec` 等不加锁，其间的零星 DB 写（审计行、编码写回）由 L1 兜底 | 避免长命令占锁把其他 agent 的写排队 60s——锁只保护毫秒级临界区 |
| L4 边界 | WAL 依赖同机本地文件系统；**禁止 project 目录位于 NFS/UNC**（SQLite 网络文件系统锁不可靠）——wrapper 初始化时检测并拒绝 | 消除唯一一类真正无解的并发故障模式 |

结论：**原"同 project 不建议 >2 并发写进程"的限制在 L1+L2 落地后不再成立**——同 project 的多进程写安全性由"毫秒临界区 + 确定性写队列 + WAL 兜底"三层保证，实际并发上限取决于任务编排而非存储引擎；若未来出现单 project 高频并行写（超出 agent 场景的合理形态），v2 出口有二：团队 PostgreSQL 数据源（真多写者 MVCC）或 project 级常驻 broker（顺带获得热 `shellCache`），均不改动本方案的接口契约。

Db.java 改动（ASCII-only，兼容 GBK 编码，C7）：

```java
// init 连接后追加三行 pragma（±6 行 diff）
try (java.sql.Statement st = dbConn.createStatement()) {
    st.execute("PRAGMA journal_mode=WAL");
    st.execute("PRAGMA busy_timeout=5000");
    st.execute("PRAGMA synchronous=NORMAL");
}
```

### 3.7 wrapper 与 JDK

wrapper 内置 JDK 探测（继承旧 skill 的 java-11 探测逻辑，GSL5 要求 JDK8+，默认用 PATH java，`GSL5_JAVA` 可覆盖）；deepstrike 侧经 `ctx.shell` 拉起（netscope 白名单继续覆盖出站连接——CLI 是 GSL5 进程，行为与旧 jar 相同）。

---

## 4. 交付物 D1：GSL5 侧改造清单

| 文件 | 改动 | 规模 |
|---|---|---|
| `src/shells/plugins/generic/CliMain.java`（**新文件**） | CLI 全部逻辑：全局选项解析、`project list/path`、L1 透传、L2 语义命令表（兼容层 8 + 扩展层 ~25，表驱动）、输出格式化、退出码 | ~450 行 |
| `src/shells/plugins/generic/McpService.java` | **仅 3 行 seam**（类末尾追加）：`public String cliCall(String name, Map args){ return toolsCall(m("name",name,"arguments",args), 1); }` 的包装——CliMain 经此访问 private toolsCall，不在热区内部动刀 | ~3 行 |
| `src/core/Gsl5Main.java` | +`"cli"` 分支转发 `CliMain.main` | ~2 行 |
| `src/core/Db.java` | WAL + `busy_timeout` + `synchronous=NORMAL`（§3.6） | ~6 行 |
| `src/core/persist/OperationLogStore.java` | 数据目录支持 `-Dgsl5.data.dir`（默认 `~/.gsl5` 不变）——审计库随 project 内聚（§3.5 硬约束 2） | ~4 行 |
| `cli/` 目录（**新**）：`SKILL.md` 手册、`gsl5` wrapper、`build-cli.sh`、`tests/compat-golden.sh` | 文档与运维资产随 fork 版本化 | ~400 行文档+脚本 |

实现纪律：业务逻辑 100% 在新文件；对既有文件的改动收敛为 **~11 行 / 3 文件**（见 §9 fork 策略）；`McpService.java` 是 UTF-8 可直接编辑，`Db.java`/`Gsl5Main.java` 用 ASCII 补丁。seam 若因上游重构失效，**编译期即失败**（优于反射的运行期才暴露），merge 时顺手修复。

## 5. 交付物 D2/D3/D4：三个消费形态

### D2 通用 skill（agent 通用性）

- **工具手册**：`/opt/tools/gsl5/SKILL.md`（D1 产物，权威内容源）。
- **可投放模板**：一份按各宿主 frontmatter 差异微调的 `pentest-gsl5` SKILL.md，分别落到：
  - dsh（deepstrike `home/profiles/*` 的 skill 目录，经 `dsh-skill-filesystem` 发现）；
  - pi（`/opt/mirrorstrike/skills/pentest-gsl5/`）；
  - Claude Code（`.claude/skills/`）——三类宿主同源，目录成本 ~100 tok/宿主，全文 ~2.5K tok 仅在 webshell 任务出现时加载。
- **内容原则**：手册讲"怎么调"（词汇/project/退出码/大文件纪律），skill 讲"何时用、怎么编排"（复用检查、落账纪律、协作协议）；skill 正文**不写凭据**，不复制手册（引用 `{BASE}/SKILL.md`，沿 mirrorstrike 既有范式 C8）。

### D3 mirrorstrike 更新（skill 切换）

- `skills/pentest-godzilla` → **`skills/pentest-gsl5`**（整替，不并存——避免双 skill 选择混乱；旧 skill 的编排协议段落原样保留）：
  - CLI 调用段：`run_gz.sh` 范式 → `gsl5 --projects-root "$PWD/tmp/state/gsl5-projects" --project main <cmd>`（JDK 探测收进 wrapper，skill 文本显著变短）；
  - **project 映射**：`--projects-root <任务工作区>/tmp/state/gsl5-projects`，project 名默认 `main`；同任务多 agent 共享同 project（保持原 data.db 共享协议），跨任务天然隔离；
  - 词汇全面切换 GSL5 原生命名（`shell_list`/`shell_exec`/`file_read`…）：skill 的「常用命令」「Payload/Cryption 速查」段按新词汇重写（新词汇更短，JDK 探测已收进 wrapper，skill 文本净减）；新增能力指引（`db_exec`、`shell_create --obfuscation`、`plugin_call`）；
  - 陷阱段保留并新增：退出码语义、`--out` 大文件、锁竞争退出码 5 的重试建议。
- 旧 `/opt/tools/godzilla/godzilla-cli.jar` 保留只读归档，一个发布周期后下线。

### D4 deepstrike 插件更新（替换 GodzillaCli）

| 文件 | 改动 |
|---|---|
| `packages/webshell/cli.ts` | `GodzillaCli` → `Gsl5Cli`：jarPath→wrapper 路径；argv 增加 `--project <sessionName>`（每个 webshell 会话一个 project，engagement 级隔离）；**方法级词汇映射**（`commandExec() → shell_exec --shellId --command` 等，Kit 工具签名不变、tests 奇偶不破）；**删除 `isWrapperCompatible`**（md5 缺陷是旧包装器特有，GSL5 完整协议不存在；以 `shell_info` 探活替代）；`call()` 退出码映射到 `CliResult.ok` |
| `packages/webshell/provider.ts` / `tools.ts` | v1 **工具面不变**（8–10 个 KitTool 签名不动，tests 奇偶锁定不破）；v2 再评估把 `shell-create`（混淆/C2）、`db-exec` 提升为 Kit 工具 |
| `config.yaml` / `config.example.yaml` | `DEEPSTRIKE_GODZILLA_JAR` → `DEEPSTRIKE_GSL5_JAR`（wrapper 路径），新增 `projectsRoot` |
| `packages/mcp/main.ts` | ms 侧装配点同步（DEFAULTS.jarPath、`--data-dir` 下 project 根对齐） |
| `tests/` | `parseCliJson` fixtures 换 GSL5 输出样例；契约测试加 8 个兼容命令的 golden 对照（S2 产出） |

---

## 6. 实施阶段与验收标准

| 阶段 | 内容 | 验收 | 估时 |
|---|---|---|---|
| **S0 全量构建（路径 B，已选定）** | 依赖三档收集：① Maven Central（flatlaf 系 / guava / pecoff4j / rsyntaxtextarea / snakeyaml / okhttp3+kotlin-stdlib / asm / sqlite-jdbc / postgresql / forms_rt）；② **自定义二进制从旧 godzilla-cli.jar 提取**（已确认内含 `com.httpProxy`、flatlaf 全家桶、pecoff4j、intellij uiDesigner——`unzip -n` 定向解包）；③ 源码内自编译资产（`dm.jar`/`kingbase.jar`/`asm.jar`/`*.classs` 由 assets 构建产出）。集齐后按 README 三段 javac（GBK 核心段 + UTF-8 McpService 段）打包 | `java -jar gsl5.jar cli version` 输出 JSON；与 Release jar 对照 `tools/list` 一致；`mcp` 模式可启动（回归） | 1–1.5 天 |
| **S1 CLI 核心 + project** | cliMain 透传 + wrapper chdir + Db pragma + 审计绑定 | ①stdout 单行 JSON 纯净（stderr 零混入）；②两进程并发 `shell-add`+写设置 50 轮无 BUSY 错误；③`--project a/b` 的 data.db 互相不可见；④退出码矩阵正确 | 1 天 |
| **S2 语义对齐** | CLI ↔ `tools/list` schema 机械映射一致性（60 工具全量枚举对比：命令名/参数名/必填集）；CLI 与 `call` 透传同工具输出语义一致性 | 真实 tomcat9 + JAVA_AES_BASE64 的 `shell_add`/`shell_exec`/`file_read` 冒烟；`--args` JSON 与 flag 两条入参路径等价性 | 0.5–1 天 |
| **S3 手册 + 通用 skill** | SKILL.md + 三宿主模板 | dsh/pi/Claude Code 三处目录条目可见、可加载 | 0.5 天 |
| **S4 mirrorstrike 切换** | pentest-gsl5 skill 替换 | 真实演练：上传→shell-add→command-exec→file-write→write_fact 全链路按 skill 走通；双 agent 同 project 共享复用验证 | 0.5 天 |
| **S5 deepstrike 切换** | Gsl5Cli + config + tests | `pnpm test` 全绿；形态 B（dsh web）驱动 webshell 全功能冒烟；fake-ms 闭环不受影响 | 0.5–1 天 |
| **S6 收尾** | 旧 jar 归档、README/AGENTS 更新、发布说明 | 文档齐；一个观察期后下线旧集成 | 0.5 天 |

**合计 ≈ 3.5–5 天**（S0 的 jar 获取是唯一外部依赖门）。

---

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| S0 jar 获取失败（无网络/依赖缺失） | 提前验证；需你方构建机出 jar 时，本方案所有 Java 改动可打成单个 patch 文件交付（改动集中在 3 文件） |
| GBK/UTF-8 混编踩坑 | C7 纪律：ASCII-only 补丁 + 新文件 UTF-8；S1 验收含中文输出乱码检查 |
| 同 project 高频写冲突 | busy_timeout + 重试 + 退出码 5 显性化；skill 写明"批量任务分 project"；团队 PG 数据源留作 v2 |
| 消费侧对 GSL5 词汇的行为差异误判（如 file_read 对二进制的返回） | S2 一致性矩阵覆盖 文本/二进制/大文件三类；deepstrike 侧由 Kit 契约测试锁定（Kit 工具签名不变，映射只发生在 `Gsl5Cli` 内部） |
| Path B 自定义依赖收集不全 | 已确认自定义项（`com.httpProxy` 等）在旧 godzilla-cli.jar 内可定向提取；构建脚本逐 jar 校验（`jar tf` 缺类即 fail-fast） |
| 双 agent 并发同 project 的误用 | skill 与插件各自固化"任务=project"映射，禁止跨任务传 project 名 |
| CLI 进程级 `shellCache` 每次冷启动（~1–3s） | 接受（与旧 jar 同量级）；批量操作用 `shell-batch-test` 合并；不做 daemon（避免引入常驻进程治理复杂度，留 v2） |
| 审计在 CLI 模式可能被认为弱化 | S1 绑定 sessionId 落库；`doctor` 输出审计可用性 |

---

## 8. 与既有文档的关系

- 前置可行性论证：[MCP-改造分析-stdio与CLI.md](MCP-改造分析-stdio与CLI.md)（SSE 不可达结论、上下文经济学、三种形态对比均继续有效；其中 P0/P0.5/P2 由本方案取代）。
- **落地实施规格：[CLI化改造实施方案.md](CLI化改造实施方案.md)（v2 · 源码核实版）**——按当前树逐条核实本方案前提（修正 3 处、补 7 项实施级事实），给出可直接执行的代码改动、构建门与阶段验收；实施以 v2 为准。
- 本方案落地后，deepstrike `AGENTS.md` 的 D2 决策（MCP 承载模型面）不受影响——那是 deepstrike↔mirrorstrike 之间的契约，与 GSL5 接入方式解耦。

---

## 9. Fork 策略：最小化改动 × 最大化兼容 × 可持续合并上游

**结论：可行，且改动分布已按 fork 场景优化完毕**——对既有文件的 in-place diff 收敛为 **~11 行 / 3 文件**（§4），其余全部是新文件；新文件在 git 合并中零冲突。

### 9.1 与上游热区的重叠度（2026-08-01 至今提交统计）

| 上游热点文件 | 近期改动次数 | 本方案是否触碰 |
|---|---|---|
| `README.md` | 25 | ✗ |
| `src/core/ui/MainActivity.java` | 16 | ✗ |
| `src/shells/plugins/generic/McpService.java` | 7（温） | 仅类末尾 3 行 seam（追加式，冲突面≈0） |
| `src/core/ApplicationContext.java` | 6 | ✗ |
| `src/core/Db.java` | 冷（未进前列） | 6 行 pragma（单点插入） |
| `src/core/Gsl5Main.java` | 冷 | 2 行分支 |

上游活跃度高（8 个 release / 2 个月），但**活动集中在 UI 与 MCP 修复，恰好都不是本方案的修改路径**——这是 fork 可行性的实证依据。

### 9.2 Git 操作模型

```bash
# ① 一次性：fork + remote 重排（当前 origin 指向上游）
#    网页 fork Xaaaa-bip/GodzillaSuper → <you>/GodzillaSuper，然后：
git remote rename origin upstream && git remote add origin <you>/GodzillaSuper.git
git fetch upstream --tags
git checkout -b cli 3.1.8          # 分叉点 = 实际运行的 release tag（与 S0 构建 jar 对应）
git push -u origin cli

# ② 日常：cli 分支开发，原子提交（cli-core / db-pragma / docs 分开，冲突好解）
git tag 3.1.8+cli.1 && git push origin cli --tags   # tag 规范：<上游版本>+cli.<序号>

# ③ 上游合并（每个上游 release 跑一轮，约 2–4 周一次）
git fetch upstream --tags && git checkout cli && git merge upstream/main
#   冲突预期面：McpService 类末尾（seam）/ Db init（pragma）/ Gsl5Main（分支）——三处皆小
./cli/build-cli.sh && ./cli/tests/compat-golden.sh   # 重建 + S2 一致性回归（合并安全网）
git tag 3.1.9+cli.1
```

**兼容的三重保障**：① CLI 词汇=GSL5 原生工具名（§3.3），上游改名即刻体现在 CLI，`compat-golden.sh` 全量枚举比对在合并当次即暴露漂移；② `call` 透传对上游**新增**工具零适配自动可用；③ 消费侧（skill/适配器）只依赖 CLI 信封契约与 L2 方法映射——上游波动被隔离在 fork 一层，不到达 deepstrike/mirrorstrike。

### 9.3 终局建议与注意事项

- **推回上游**：CLI 属增量能力、贴合上游"CLI 无头模式"方向；上游 PR 走 `dev` 分支（观察自其 PR 合并记录）。合入后 fork 的 diff→0，自然解散——这是最优终局，fork 期间保持提交整洁即为 PR 铺路。
- **许可证**：仓库根目录**无 LICENSE 文件**（仅免责声明）——内部使用无碍；若 fork 计划公开分发，先与上游作者确认授权条款。
- **每次合并后的必做项**：重新构建 jar（源码↔产物对应）+ `compat-golden` + `doctor` 冒烟；`data.db` 格式若上游变更，project 目录按 `<projects-root>/<project>` 原地迁移（SQLite 自兼容旧 schema 由上游迁移逻辑负责，回归验证覆盖）。
- **fork 尚未创建**（当前本仓库 origin 仍指上游）：按 §9.2 ①执行即可；本方案所有改动在 `cli` 分支进行，`main` 保持镜像不落提交。
