# Linux 现场验收辅助脚本

这是 2026-10-28 固定数据/布局的一次验收工具，**不是通用部署器**。硬性约束 root、unit、条数、
原版本和候选哈希；条件改变后须重新审查。管理员通过 SSH 本地执行，不新增公网端口。

- `server_verify.py`：prepare → start → seed → restart → cleanup；20 条公共知识、A/B 非空数据、
  SSE 取消、真实问答/笔记与重启。runId 必须唯一，prepare 不覆盖目录。
- `migration_verify.py`：backup → migrate → rollback；backup 会短暂停生产。私人数据只在服务器，
  恢复目标必须空，来源逐文件验证。回滚须配套旧数据，不能只切 JAR。
- `full_knowledge_verify.py`：expand → evaluate；明确输入与 SHA，预算不超过 2MB UTF-8，
  指定云模型、HTTP 尝试次数 1，每批留进度。启动探针超时可 verify-restart，避免重复付费。
- `production_cutover.py`：显式 publish 与提交版本。先过全量评测，再停写归档校验；失败保留
  新现场并恢复成套旧备份。使用原管理员口令，临时普通账号复用服务创建，验后停用。

`mvn test-compile` 后上传测试类到 probe-classes 对应包路径：KnowledgeSearchProbe.class、其
`$1.class` 和 AccountSmokeProbe.class。由 Boot PropertiesLauncher 调用，仅复用业务 JAR 依赖，
不把测试入口打进业务包，不新增线上后门。

不要下载或提交数据库、verify.env、真实数据归档、生产 env、日志全集。下载 evidence/ 前检查仅含
合成数据、公共知识和脱敏计数/摘要；响应内容限本轮自造请求。cleanup 只停隔离 unit、删除临时
凭据，保留审计数据，不清空生产目录、不递归删除目录。启动等待 180 秒；UNKNOWN 与调用失败、
TERM143 与 OOM 都必须分别判断。
