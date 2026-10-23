# 第一阶段验收记录（本地演练）

日期：2026-10-23 ｜ 代码状态：部署改造工作树 ｜ 环境：Windows 11 + JDK 17 兼容运行（当前 JVM 25 也可跑）+ 本地 llama.cpp 8090

> 重要：**尚未完成云端/Docker 实测**。本文件只记录本机可复现的验证；Docker 未安装，目标服务器信息未提供。

## 1. 自动测试

```text
mvn -q test
TOTAL=399 FAIL=0 ERR=0 SKIP=0
```

新增重点：

- `SecurityAccessTest`：未登录 `/study.html` 302 登录、业务 API 401；登录后 `/api/auth/me` 200。
- `RateLimitInterceptorTest`：每分钟限额内通过，超限 429。
- `RetrievalEvalTest`：真实 8090 embedding + 真库，未跳过。

## 2. 访问保护（手工 HTTP）

| 请求 | 未登录 | 登录后 |
|---|---|---|
| `/` | 200 | 200 |
| `/study.html` | 302 → `/login.html` | 200 |
| `/api/memory` | 401 | 200 |
| `/api/conversations` | 401 | 200 |
| `/api/auth/me` | 401 | 200（`pengge`） |
| `/api/health` | 200（公开） | 200 |

登录页见 `tmp/ttb-login.png`（本地临时截图）。

## 3. SSE

- 未登录：进不了 SSE（401）。
- 登录后：`POST /api/agent/stream` 返回 `200 text/event-stream`，先 `meta`；当前机器**没有配置 DeepSeek Key**，随后收到明确的 `error` 事件（不是静默挂起）。
- 文字/图片真实问答：**未执行**（没有模型 Key；未为了验收去消耗真实额度）。

## 4. 向量服务

- 本地 llama.cpp + `bge-m3-Q8_0.gguf`（`start-embedding.bat`）启动后：
  - `/api/health`：`embedding.status=UP`，`degraded=false`。
  - `RetrievalEvalTest` 真库：`recall@5=0.85`、`MRR=0.77`、`页命中@5=0.88`、`拒答率=1.00`。
- 停掉 llama-server 后：`/api/health` 立即显示 `embedding.status=DOWN`、`degraded=true`（不会把纯关键词检索说成语义检索）。
- 云端 embedding：只准备了配置模板（URL/model/key/health-path），**未实测**。

## 5. 数据持久化与恢复

### 5.1 备份 → 隔离恢复（小样本）

- 用 `scripts/backup.ps1` 备份 fixture；
- `scripts/restore.ps1` 恢复到独立目录；
- 恢复出 `data/conversations`、`data/memory`、`knowledge/base.json`、`sessions`，文件数/内容核对通过。

### 5.2 真实数据恢复 → 程序读取

- 备份真实项目 `data` + `knowledge` + `sessions`（`talk-to-textbook-20261023-174221.tar.gz`）；
- 恢复到 `tmp/real-restore`；
- 用**另一实例**（8096）指向恢复目录启动，登录后读取：
  - `/api/conversations`：3 个会话；
  - `/api/memory?conversationId=default`：1 条记忆；
  - `/api/knowledge?sample=1`：12925 条知识；
  - `/api/health`：UP。
- 验证结束后停止 8096；**没有覆盖真实数据**。

## 6. 资源边界

- 限流：同一登录会话连续 13 次 `/api/agent`，前 12 次 400（缺 Key/参数），第 13 次 **429**。
- SSE 线程池：固定 4 线程 + 队列 8，满则回 `error` 事件“当前请求较多”。
- 模型并发：Semaphore 2，等 10 秒仍无名额 → 503（`OverloadedException`）。
- 工具结果 6000 字符截断；上传 100MB/请求 120MB/图片 10 张/Agent 6 步沿用原边界。

## 7. 部署文件

已提供：

- `Dockerfile`（Maven 构建 → JRE17 运行，非 root，healthcheck）
- `docker-compose.yml`（app + 本地 embedding + 可选 nginx profile；数据 bind mount；内存上限）
- `.env.example`（无真实凭据）
- `deploy/nginx.conf`（SSE 关 buffering、超时、HTTPS 注释模板）
- `scripts/backup.sh` / `restore.sh` / `backup.ps1` / `restore.ps1`
- `docs/deploy.md`

## 8. 未执行 / 已知限制

- **未构建 Docker 镜像**：本机没有 Docker；`docker-compose.yml` 只做了 YAML 解析校验（services=app, embedding, nginx）。
- **未上云**：没有服务器 IP/域名/证书；HTTPS 未实测。
- **未跑真实文字/图片问答**：没有模型 API Key；SSE 只验证了鉴权与错误事件。
- **SSE 取消**：只能做到步骤之间停手；已经开始的一次模型 HTTP 请求不能强杀。
- **单用户**：没有多租户隔离、注册、找回密码。
- **限流/会话**：单机内存，重启清零；多实例需共享存储。
- **Docker 镜像 tag**：`ghcr.io/ggml-org/llama.cpp:server` 需在目标机器确认可拉取，备选 `ghcr.io/ggerganov/llama.cpp:server`。
