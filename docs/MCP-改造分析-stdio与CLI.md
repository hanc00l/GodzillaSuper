# GSL5 MCP 现状分析与 stdio/CLI 改造可行性报告

> 分析对象：GodzillaSuper（GSL5 3.1.8）内置 MCP 服务（`src/shells/plugins/generic/McpService.java`，3641 行）
> 目标消费方：deepstrike（dsh 宿主插件套件，`/opt/deepstrike`）与 mirrorstrike（Go 控制面 + pi 执行器，`/opt/mirrorstrike`）
> 分析日期：2026-09-28 · 基于源码逐行核实，非文档转述

---

## 0. 结论摘要（TL;DR）

1. **当前 SSE 版 MCP 对"设计受众"（Claude Desktop / Claude Code / Codex）基本够用，但用的是已废弃的 legacy SSE 传输（协议 2024-11-05），且实现有多处协议异味**。
2. **对你们真正的两个 agent 而言，当前版本不可用**——这是决定性结论：
   - mirrorstrike 的 pi 扩展（`mirrorstrike-mcp.ts`）**只实现了 stdio 客户端**（`StdioClientTransport`）；
   - dsh 宿主的 `packages/mcp/mcp-client` **只支持 stdio + Streamable HTTP**，没有 legacy SSE 客户端。
   - 即：GSL5 只会说"legacy SSE"，两个 agent 都不会"听"这门方言。**双方无交集。**
3. **改造为 stdio MCP：完全可行，且成本低**。`dispatch(method, params, id)` 已是传输无关的纯函数（入参 JSON-RPC method/params/id，返回 JSON 字符串），加一个 stdin/stdout 的 NDJSON 主循环 ≈ 200~300 行 Java，即可同时打通两个 agent。
4. **改造为 CLI：同样可行，且有现成生态位**。deepstrike 的 webshell 包现在包装的是第三方旧 `godzilla-cli.jar`（`Main-Class: CliMain`，存在 md5 对齐缺陷、PHP 系不可用、词汇受限等已记录缺陷）；GSL5 做一个通用 `call <tool> --json` 透传子命令（≈ 100 行）即可 1:1 顶替它，并解锁 PHP / ASPX / NetCore / 达梦·金仓 / C2 容器链 / 混淆变体等全部能力。
5. **推荐路线**：P0 加 stdio 模式（一个模式两端通吃）→ P1 加通用 CLI 透传（顶替旧 jar + 人工调试）→ SSE 保留给远端/多客户端场景，P2 可选升级 Streamable HTTP。

---

## 1. 现状：GSL5 MCP 实现剖析

### 1.1 传输与协议

| 项 | 现状 | 评价 |
|---|---|---|
| 传输 | HTTP + SSE（`GET /sse` 建流 + `POST /message?sessionId=` 发请求） | **legacy SSE transport，MCP 2025-03-26 起废弃**；现行规范（2025-06-18 / draft）只保留 **stdio + Streamable HTTP** |
| 协议版本 | `initialize` 固定回 `protocolVersion: "2024-11-05"`，不回显客户端请求版本 | 旧但多数客户端容忍 |
| 端点 | `/sse` `/message` `/health` `/config` | `/config` 可 GET 读 `config.yaml`（含团队 PG 密码）、POST 覆写——仅 token 一层防护 |
| 鉴权 | `Authorization: Bearer` / `X-MCP-Token` / `?token=`，constant-time 比较；非 loopback 绑定强制 token，loopback 无 token 放行 | 设计合理；但默认绑定 `0.0.0.0:9123` 对本机 agent 场景过宽 |
| CORS | `*` 全开 | 配合 `?token=` 传参，token 可能进代理/日志 |

### 1.2 实现质量（逐条核实自源码）

1. **用 SnakeYAML 解析 JSON-RPC 请求体**（`MessageHandler`：`Map req = yaml.load(body)`）。JSON 恰好是 YAML 子集所以"碰巧能跑"，但：
   - YAML 1.1 与 JSON 语义存在差异面（数字键、sexagesimal、特殊标量），属于隐性问题源；
   - `new Yaml()` 默认构造是 CVE-2022-1471 的不安全构造（全局 tag 反序列化）。严格 JSON 请求体因字符串必带引号而难以直接注入 `!!` tag，但这属于"侥幸不可达"而非"设计不可达"。**应换真正的 JSON 解析器**（3.1.8 修的"非法 JSON 打断 SSE"就是这一类问题的表征）。
2. **响应双路发送**：`POST /message` 既 `pushSse(result)`（走 SSE 流）又 `sendJson(202, result)`（写响应体）。legacy SSE 规范中 POST 应只回 202 Accepted、响应走 SSE 流；多数客户端忽略 body，属协议异味，个别严格客户端可能重复消费。
3. **`pushSse` 的单会话兜底**：sessionId 为空且恰有一个 SSE 会话时盲推——单客户端可用，多客户端语义未定义。
4. **长调用全程阻塞**：`tools/call` 在 POST 线程同步执行，无 job/异步模式。Mimikatz、大文件分片、批量测试等可能超过客户端超时（dsh `mcp-client` 默认 `toolCallTimeoutMs: 60_000`），表现为"客户端报超时、服务端仍在跑"。
5. **无 `ping` / `resources/list` / `prompts/list`**，未知 method 一律 `-32601`：仅声明 tools capability，自洽，但部分客户端探活会报错。
6. **工具规模**：内置 50 个 + `@McpTool` 插件约 10 个（NewCmd×3、TH_TOOLS、Mimikatz、Useradd、EfsPotato、OaTools、ShellAvscan、ClassLoader）≈ **60 个工具平铺在 tools/list**。schema 全中文描述质量不错（含使用提示），但对 LLM context 是可观负担（粗估 10KB+ 指令面）。
7. **头部成熟度**：3.1.8 修掉了无头模式 `HeadlessException`、SSE 断流、`file_search` NPE 等硬伤；`GOptionPane.SUPPRESS_UI` 免弹窗、编码自动探测写回 DB，这些对 agent 使用都是必要的且已做好。

### 1.3 值得肯定的部分

- `dispatch()` 把协议处理与传输完全解耦——这是本次改造可行的最大利好；
- Token 鉴权用 constant-time 比较、非 loopback 无 token 拒启，安全意识在线；
- `shellCache`（已初始化 Shell 的进程内缓存）+ 编码探测缓存，让热连接的后续调用明显加速——**长驻进程（stdio）能吃满这个红利，一次性 CLI 则每次冷启动**；
- 全量操作审计（`oplog_query`）对多 agent 场景是刚需能力，已具备。

---

## 2. 决定性事实：两个 agent 的 MCP 消费能力

### 2.1 消费方传输矩阵（源码核实）

| 消费方 | 实现位置 | 支持的传输 | 能否直连当前 GSL5 SSE |
|---|---|---|---|
| mirrorstrike / pi | `pi-agent/extensions/mirrorstrike-mcp.ts` → `StdioClientTransport`；经 `pi-mcp-adapter` 读 `mcp/mcp.json`（`command`/`args`/`env`/`direct_tools`） | **仅 stdio** | ✗ 不能 |
| deepstrike / dsh 宿主 | `deepseek-harness/packages/mcp/mcp-client`：`transport: 'stdio' \| 'streamable-http'`，仅导入 `StdioClientTransport` + `StreamableHTTPClientTransport` | stdio + Streamable HTTP | ✗ 不能（dsh 说 streamable-http，GSL5 说 legacy SSE，方言不通） |
| deepstrike / ms 侧适配器 | `packages/mcp/main.ts`（`node dist/mcp/main.js`）——自己是 **stdio server** 供 ms 拉起；webshell Kit 内部包装旧 `godzilla-cli.jar` 子进程 | stdio（server 侧） | 不适用 |
| Claude Code / Desktop | 官方客户端 | stdio + legacy SSE + Streamable HTTP | ✓ 能 |
| Codex CLI | `[mcp_servers.x]` | stdio；HTTP 支持随版本演进（社区记录其主线为 Streamable HTTP），legacy SSE 无保证 | ~ 视版本 |

> 参考现行 MCP 规范：[Transports (2025-06-18)](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) 与 [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)——stdio 与 Streamable HTTP 是仅有的两个标准传输，HTTP+SSE 已于 2025-03-26 版废弃。

### 2.2 结论

"当前 MCP 版本是否足够和合理"要拆开答：

- **对 Claude/Codex 生态**：够用、合理（补齐 token 鉴权、免弹窗、headless 都做对了），但压在一条已废弃的传输上，属于技术债，非急迫。
- **对 deepstrike + mirrorstrike**：**不足够**——不是工具能力不足（60 个工具覆盖面远超两个 agent 现在从旧 godzilla-cli.jar 拿到的 8 个子命令），而是**传输不通**：一个只讲 stdio，一个只讲 streamable-http，GSL5 只有 legacy SSE。

---

## 3. 改造可行性评估

### 3.1 stdio MCP：可行，低成本，一次改造两端通吃

**依据**：
- `dispatch(method, params, id)` 是纯函数：吃 JSON-RPC 三元组、吐 JSON 字符串，`SseHandler/MessageHandler` 只是它的 HTTP 皮；
- `startHeadless()` 已验证整套无头初始化链路可用（免弹窗、headless 插件工具、Db 初始化）；
- stdio 传输规范极简：**换行分隔的 JSON-RPC 消息进 stdin、出 stdout**（非 LSP 的 Content-Length 帧），无握手帧、无心跳要求。

**改造清单（P0，估 200~300 行，全部在 McpService.java 追加）**：
1. `Gsl5Main` 增加 `"stdio"` 分支：`java -jar gsl5.jar stdio`；
2. `StdioMain`：`System.in`（显式 UTF-8 解码）逐行读 → `dispatch()` → 结果 + `\n` 写 `System.out`（显式 UTF-8 字节）→ `flush()`；收到 `exit` 或 EOF 退出；
3. **stdout 纯净化**：stdio 模式下所有启动横幅/Token 打印/`[MCP]` 日志（现为 `System.out.println`）重定向 stderr；`Log.log` 输出同样处理。这是唯一需要小心的点——stdio 模式 stdout 上任何非协议字节都会打崩客户端解析；
4. 诊断日志走 stderr：mirrorstrike 的 pi 扩展已经把子进程 stderr 管道转发进任务日志（`mirrorstrike-mcp.ts` 的 `stderr: "pipe"` 分支），dsh 侧同样捕获子进程 stderr——现成的观测通道；
5. 不需要 token/绑定/端口（无网络面），但建议校验父进程为本机（stdio 天然如此，无需额外代码）；
6. 可选：`GSL5_MCP_TOOLS` 环境变量做工具白名单，在 `toolsList()` 过滤——**用 30 行解决 60 工具 context 膨胀**，并让不同 agent 只见到自己角色的工具（mirrorstrike 的 `direct_tools` 也能做这件事，但服务端过滤对 dsh 侧同样生效）。

**收益**：
- mirrorstrike：`mcp.json` 直接挂载，零代理进程；
- deepstrike：dsh 的 `mcp-client`（stdio）直接挂载，**零代码**；
- 长驻 JVM 吃满 `shellCache` 热连接红利（对比一次性 CLI 每次 1~3s 冷启动）；
- 生命周期跟随 agent：agent 退出进程即回收，无孤儿服务、无端口管理。

**风险与对策**：
- *JVM 启动成本*：每次 agent 会话启动一次（非每工具调用），可接受；
- *多 stdio 实例并发写 `data.db`*：多个 agent 各拉起一个 stdio 进程、共享同一 CWD 的 SQLite 时，写并发（尤其 oplog 每操作必写）可能撞 `SQLITE_BUSY`。对策：① 固定单一共享实例思路（见 §5 架构选项 B）；② 团队 PostgreSQL 数据源；③ 至少把各 agent 的 `data.db` 隔离在不同 CWD（代价是 shell 列表不同步）。

### 3.2 CLI：可行，且在 deepstrike 有现成生态位

**两层设计**：
1. **通用透传（P1 核心，≈100 行）**：
   ```
   java -jar gsl5.jar call <toolName> --json '{"shellId":"3","command":"whoami","encoding":"auto"}'
   ```
   直接把参数喂给现有 `toolsCall()` 的 switch——**一次开发，60 个工具全量暴露**。输出收敛为单行 JSON（`{"ok":true,...}`）到 stdout，日志走 stderr。
2. **语义子命令（按需糖衣）**：`shell-exec` / `file-ls` / `db-exec`… 仅在调用频度证明需要时再加。

**deepstrike 侧的对接**：`packages/webshell/cli.ts` 的 `GodzillaCli` 类已经是"spawn `java -jar` + 从混合输出中抠第一行 JSON"的适配器（`parseCliJson`），且文档明确记录了旧 jar 的三大缺陷（md5 对齐缺陷、PHP 系不可用、CWD 治理）。**换 jarPath + 补子命令映射**即可让 GSL5 CLI 顶替旧 `godzilla-cli.jar`，能力从 8 个受限子命令跃升到全谱（PHP 无 exec 命令执行、达梦/金仓、NetCore、C2 容器链、6 种混淆、批量测试、存活管理）。注意 `isWrapperCompatible` 的 md5 前导零校验逻辑在换 GSL5 后可评估移除（GSL5 是完整协议实现，不存在该包装器缺陷——建议实测回归后处理）。

**一次性 CLI 的固有成本**：每次调用冷启动 JVM + ApplicationContext + Db（约 1~3s）。deepstrike 现有模式（60s 超时）已容忍同量级开销；批量操作建议组合进单次 `call`（如 `shell_batch_test`）或走 stdio 通道。

### 3.3 零改造过渡方案（如需"今天就能用"）

stdio↔SSE 桥接代理（`mcp-remote` / `supergateway`）：
```json
{ "command": "npx", "args": ["-y", "mcp-remote", "http://127.0.0.1:9123/sse",
  "--header", "Authorization: Bearer ${GSL5_TOKEN}"] }
```
- 优点：Java 侧零改动，两个 agent 立即可用；
- 缺点：每个 agent 多一层 node 常驻进程、token 处理易错、仍押注已废弃传输、断线重连行为取决于第三方桥。**只建议作为 P0 落地前的临时验证手段。**

---

## 4. SSE vs stdio vs CLI 全面对比

| 维度 | HTTP+SSE（现状） | stdio MCP（建议 P0） | CLI 子进程（建议 P1） |
|---|---|---|---|
| 客户端兼容 | Claude 系 ✓；**mirrorstrike ✗、dsh ✗**；Codex 视版本 | **几乎所有 MCP 客户端的标准配置**（两个 agent 原生支持） | 不依赖 MCP；任何能 spawn 进程的 agent 都能用（deepstrike 现成模式） |
| 协议现代性 | 已废弃（2024-11-05 legacy） | 现行标准，长期稳定 | 无协议版本问题 |
| 进程/生命周期 | 服务端常驻，独立启停 | 客户端拉起、随会话回收，pi/dsh 都内置崩溃重启与 stderr 转发 | 每调用一进程（或配合常驻 daemon） |
| 多客户端并发 | ✓ 天然多客户端共享同一 JVM 状态 | 每 agent 一实例，状态经共享 `data.db` 间接同步（有 SQLite 写并发注意项） | 同左，且每次调用独立 |
| 远程访问 | ✓ 跨机（token 鉴权） | ✗ 本机（跨机需 SSH/隧道） | ✗ 本机 |
| 网络暴露面 | TCP 端口 + 4 路由（`/config` 可读写 config.yaml）；默认绑 `0.0.0.0` | **零网络面**（继承进程权限） | 零网络面 |
| 状态热度 | 单实例 `shellCache` 全员共享 | 单 agent 会话内热（1 次冷启动） | 每次调用冷启动 1~3s |
| 长任务表现 | HTTP 流 + 15s 心跳；受客户端超时约束 | 同受客户端 `toolCallTimeoutMs` 约束（dsh 默认 60s）；断线即会话终止 | 进程退出码即结果；超时由调用方控制，语义最直白 |
| 调试便利 | `curl /health`、浏览器看流，最容易 | 需 echo JSON 进 stdin 或 MCP Inspector | 最直白（命令行即所得），也是人工排查首选 |
| agent 集成成本 | 两个 agent 均需桥接 | **两边都是一行配置** | deepstrike 改一个适配器文件；mirrorstrike 不适合（无 spawn 工具约定） |
| 适用角色 | 远端协作、多客户端共享、GUI 配套 | **mirrorstrike 主通道 + deepstrike 备选** | **deepstrike 主通道 + 全员调试工具** |

**一句话**：SSE 的价值在"远程 + 多客户端共享"，恰好不是你们两个本机 agent 的需求形状；stdio/CLI 的"随会话生灭、零网络面、生态标准"正中靶心。

---

## 5. 推荐架构与接入拓扑

### 架构选项

- **选项 A（推荐基线）：双通道**
  - mirrorstrike → GSL5 **stdio**（pi 原生拉起）；
  - deepstrike → GSL5 **stdio**（dsh `mcp-client` 挂载）或 **CLI**（`GodzillaCli` 适配器顶替旧 jar）；
  - 同一 CWD 固定数据目录；并发写敏感场景切团队 PostgreSQL。
- **选项 B（多 agent 强一致需求时）：单实例权威 + stdio 桥**
  - 跑一个 GSL5 服务（loopback + token）作为唯一状态权威，各 agent 经桥接/代理挂上去。状态绝对一致，代价是代理进程与服务生命周期管理。
- **不推荐**：继续扩展 SSE 为唯一通道（两个 agent 都要加桥，且押注废弃传输）。

### 配置示例

mirrorstrike `mcp/mcp.json`（stdio 直连）：
```json
"gsl5": {
  "command": "/opt/tools/gsl5/gsl5-stdio.sh",
  "direct_tools": ["shell_exec", "file_list", "file_read", "db_exec", "shell_info"]
}
```
`gsl5-stdio.sh`（固定工作目录 + stdout 纯净保证）：
```bash
#!/bin/sh
cd /opt/tools/gsl5
exec java -Dfile.encoding=UTF-8 -jar gsl5.jar stdio
```

dsh / deepstrike 侧（`cordis.patch.yml` 的 mcp-client 配置）：
```yaml
transport: stdio
serverName: gsl5
command: /opt/tools/gsl5/gsl5-stdio.sh
toolCallTimeoutMs: 120000   # 长任务（Mimikatz/大文件）放宽
```

deepstrike CLI 通道（`webshell/cli.ts` 换实现）：
```bash
java -jar /opt/tools/gsl5/gsl5.jar call shell_exec \
  --json '{"shellId":"3","command":"whoami","encoding":"auto"}'
```

### 落地路线图

| 优先级 | 事项 | 工作量 | 效果 |
|---|---|---|---|
| P0 | `mcp-stdio` 模式（NDJSON 主循环 + stdout 纯净化 + UTF-8） | 200~300 行 Java | mirrorstrike/dsh 双双原生接入 |
| P0.5 | `GSL5_MCP_TOOLS` 工具白名单 | ~30 行 | 按角色裁剪 60 工具，降 context 压力 |
| P1 | 通用 `call` CLI 透传 | ~100 行 | deepstrike 顶替旧 godzilla-cli.jar；人工调试通道 |
| P1.5 | 顺手修复：JSON 解析器替换 `yaml.load`；`/config` 收敛；默认绑定改 `127.0.0.1` | 小 | 消除协议异味与暴露面 |
| P2 | SSE 保留并可选升级 Streamable HTTP（2025-03-26+） | 中 | Claude/Codex 生态前瞻；对两 agent 非必需 |
| P2 | 长任务异步化（job id + 轮询工具） | 中 | 解决 60s 客户端超时与 Mimikatz/大文件的错配 |

### 安全注意事项（agent 化后的新增面）

1. agent 拿到的是"在授权目标执行任意命令/文件/SQL"的完整能力：deepstrike 侧已有审批 UI + netscope 出站白名单（stdio JVM 经 `ctx.shell` 拉起同样受控）；mirrorstrike 侧有 guard/`direct_tools` 白名单——**建议在 `direct_tools`/`GSL5_MCP_TOOLS` 双层都做最小授权**，`mcp_config`、`config_write`、`settings_set` 这类"改配置"工具默认不对 agent 开放；
2. stdio 模式无 token（无网络面），但要防"shell 注入进 args"：透传 CLI 的 `--json` 参数务必走文件/argv 而非拼接 shell 字符串（deepstrike 的 `shellQuote` 模式已正确示范）；
3. 审计已内置（`oplog_query`），多 agent 并发时它就是"谁干了什么"的回溯依据——保持开启；
4. `data.db` 与 `gsl5://` 分享链接含密码密钥，多 agent 共享工作目录时收紧文件权限。

---

## 6. 补充分析：Skills / 插件方式 vs 全量 MCP 的上下文经济学

> 追加问题：MCP 一次性暴露太多 tools 会占用大量 token/上下文，skills 方式、插件方式是否更优？

### 6.1 量化：GSL5 全量 tools/list 的真实成本

从源码还原实测：49 个内置工具的 tools/list JSON ≈ **10.3 KB**，加 10 个 `@McpTool` 插件工具后 ≈ **13–14.4 KB**，即 **≈ 3000–5500 tokens 的常驻上下文**——每个 agent、每一轮对话都占。这还不算选择精度问题：60 个平铺工具中存在大量语义重叠（`file_read` vs `file_download_local` vs `shell_exec cat`），平铺工具列表超过 ~20 个后模型路由准确率明显下降，这是比 token 更实际的代价。

### 6.2 关键事实：三种方式在你们的体系里都已存在且各有验证

1. **mirrorstrike 已经用 skill 方式驱动旧 godzilla CLI**：`/opt/mirrorstrike/skills/pentest-godzilla/SKILL.md`——通过 bash 直接调 `java -jar godzilla-cli.jar`，**0 个 MCP 工具 token**。skill 里沉淀了完整工作流纪律：多 agent 共享 `data.db` 的编排协议、JDK 11 强制、`-Duser.dir` 陷阱、复用检查、write_fact 状态落账、参数陷阱（base64 content、引号嵌套、大文件走 `--file`）。pi 侧 skill 仅在 webshell 任务出现时按需加载（目录条目 ~60–100 tok 常驻，全文 ~2K tok 按需）。
2. **deepstrike 本身就是"插件方式"**：webshell 包把 CLI 包成 8–10 个精选 KitTool（高内聚、带护栏、schema 强类型），`packages/mcp/main.ts` 把四包能力以精选工具集暴露给 ms。
3. **dsh 有完整的 progressive-disclosure skill 体系**：`dsh-skill`（注册表）+ `dsh-skill-filesystem`（本地 SKILL.md 发现）+ `dsh-tool-skill`（模型面目录 + `skill` 按需加载工具，目录 description 截断上限 500 字符）。

### 6.3 各方案对比

| 方案 | 常驻上下文 | 按需成本 | 操作可靠性 | 能力覆盖 | 改动成本 |
|---|---|---|---|---|---|
| 全量 MCP（60 工具） | ~3–5.5K tok/agent | 0 | 高（schema 强类型护栏） | 100% | 零 |
| MCP + 白名单（8 个精选） | ~1.5–2K tok | 0 | 高 | 白名单内；改配置需重启 | 低（mirrorstrike 用 `direct_tools`；**dsh 的 mcp-client 无按 server 过滤工具的配置项**，需服务端 `GSL5_MCP_TOOLS` 过滤） |
| 聚合元工具（discover + call 两个） | ~300–500 tok | 每次多一跳发现调用 | 中（弱 schema，模型自己拼 name/args） | 100% | 中（服务端加 2 个元工具） |
| **Skill 方式**（CLI + SKILL.md） | ~60–100 tok 目录 | 全文 ~2K tok 仅任务相关时加载一次 | **中低**（自由文本拼 bash，参数错误率高——pentest-godzilla 里大量陷阱条款正是为此付出的维护代价） | 100%（CLI 透传） | 低（他们已有完整范式） |
| **插件方式**（deepstrike 现状） | ~2K tok | 0 | **最高**（工作流形工具 + 护栏 + 测试锁定） | 精选面，扩展需写适配器 | 高（每能力一个适配器） |

### 6.4 结论与建议

**Skills/插件确实更优，但三者不是互斥关系，最优解是分层组合**：

- **工具 schema 承载"怎么调"**（强类型、低错误率）；**skill 承载"何时用、怎么编排、怎么协作"**（工作流纪律、多 agent 协议、陷阱）——后者 MCP 工具 schema 表达不了，是互补而非替代。
- **mirrorstrike**：维持现有 `pentest-godzilla` skill + bash CLI 模式（已验证、0 常驻工具 token），把 CLI 换成 GSL5 的 `call` 透传即可，skill 文本增量更新（复用/落账协议不变，命令词汇扩充）。不建议给它上全量 MCP。
- **deepstrike**：维持插件方式为主（Kit 精选工具质量最高）；若要走 MCP 通道，**必须配合服务端白名单**（dsh mcp-client 没有 direct_tools 等价物，60 工具会全量进来）——`GSL5_MCP_TOOLS` 过滤从原报告的 P0.5"优化项"升级为 MCP 路线的**必选项**。
- **长尾能力**（提权插件、C2 模板、达梦金仓等低频工具）：不进白名单，用 skill 文本 + 元工具/CLI 透传按需触达。
- 工程优先级相应调整为：**P0 stdio 不变；P0.5 `GSL5_MCP_TOOLS` 提升为必做；P1 CLI 透传的地位上升**（它同时服务 skill 方式的 deepstrike/mirrorstrike 与人工调试）。

---

## 7. 附：核实过的事实清单

- `McpService.java`：`com.sun.net.httpserver.HttpServer`；`/sse`/`/message`/`/health`/`/config` 四上下文；`dispatch()`（L1184 附近）传输无关；`MessageHandler` 用 `yaml.load` 解析请求体；`pushSse` + `sendJson(202, result)` 双路响应；`initialize` 固定 `2024-11-05`；token 支持 Bearer/X-MCP-Token/`?token=` 且 constant-time 比较；非 loopback 无 token 拒启。
- `Gsl5Main.java`：`args[0]=="mcp"` 进无头 MCP；headless 环境自动 fallback MCP 模式——CLI 分派点现成。
- 内置工具 50 个（`toolsList` 硬编码）+ `@McpTool` 插件工具约 10 个（NewCmd×3、TH_TOOLS、Mimikatz、Useradd、EfsPotato、OaTools、ShellAvscan、ClassLoader）。
- mirrorstrike：`internal/mcp/server.go` 自身是 stdio server（`mcp.StdioTransport`）；`pi-agent/extensions/mirrorstrike-mcp.ts` 仅 `StdioClientTransport`；`mcp/mcp.json` 支持 `command/args/env/direct_tools`（direct_tools 由 `pi-mcp-adapter` 过滤下发）。
- deepstrike：`packages/webshell/cli.ts` 包装 `/opt/tools/godzilla/godzilla-cli.jar`（`Main-Class: CliMain`，第三方旧包装器，文档记录 md5 对齐缺陷/PHP 不可用）；`packages/mcp/main.ts` 是 ms 侧 stdio server；dsh `packages/mcp/mcp-client` 仅 stdio + Streamable HTTP，`DEFAULT_TOOL_CALL_TIMEOUT_MS = 60_000`。
