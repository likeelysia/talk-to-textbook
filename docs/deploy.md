# 部署手册（第一阶段：个人受控云部署）

> 目标：把现有 Agent 部署成**只有本人/受邀者能访问**的学习工具；重启不丢数据，故障可识别，资源有边界。
> 当前状态：单用户受控版本，**不宣称已具备多用户数据隔离**。

## 1. 先看结论

- 访问保护：Spring Security 表单登录，单用户；首页/登录页公开，学习页和所有业务 API 需要登录。
- 数据：`knowledge/`、`data/`、`sessions/`、`logs/`、`dict/` 都是**挂载目录**，更新镜像不会覆盖。
- 向量：默认本地 `llama.cpp` 容器跑 bge-m3；也可切任意 OpenAI 兼容云端 embedding。
- 资源：SSE 线程池、排队、模型并发、每分钟请求数都有上限；超出会给明确错误。
- 部署：`Dockerfile` + `docker-compose.yml` + Nginx + 备份/恢复脚本。
- **尚未完成云端实测**：本机没有安装 Docker，也没有目标服务器信息；本文中的命令是待执行方案，最终报告会区分“本地演练”和“云端实测”。

## 2. 服务器建议

| 项目 | 最低 | 建议 |
|---|---|---|
| CPU | 2 核 | 4 核 |
| 内存 | 4 GB（本地 embedding 会比较紧） | 8 GB |
| 磁盘 | 20 GB | 40 GB+ |
| 系统 | Linux x86_64 | Ubuntu 22.04/24.04 |
| 带宽 | 3 Mbps | 5 Mbps+ |

内存估算：JVM 约 1–2 GB；bge-m3 Q8 本地服务约 1.5–2.5 GB；系统/构建余量 1 GB。2C4G 只建议用云端 embedding，或把 `APP_MEM_LIMIT`/`EMBEDDING_MEM_LIMIT` 压小并实测。

## 3. 环境变量

复制 `.env.example` 为 `.env`，至少填：

| 变量 | 用途 | 必填 |
|---|---|---|
| `FA_AUTH_USERNAME` | 登录名 | 是 |
| `FA_AUTH_PASSWORD_HASH` | BCrypt 哈希（推荐） | 二选一 |
| `FA_AUTH_PASSWORD` | 明文口令（只读环境变量，启动时编码，不落盘） | 二选一 |
| `FA_ADMIN_KEY` | 入库管理接口的 `X-Admin-Key` | 公网必填 |
| `FA_DEEPSEEK_API_KEY` | 文本模型 | 问答必填 |
| `FA_VISION_API_KEY` | 图片/OCR 模型 | 图片功能必填 |
| `FA_EMBEDDING_URL` | embedding 地址 | 是 |
| `FA_EMBEDDING_MODEL` | 模型标识 | 是 |
| `FA_EMBEDDING_API_KEY` | 云端 embedding 的 Bearer | 云端必填 |
| `FA_COOKIE_SECURE` | HTTPS 下设为 `true` | 正式部署 |

生成 BCrypt 哈希（Linux 服务器）：

```bash
sudo apt-get install -y apache2-utils
htpasswd -bnBC 12 "" '你的口令' | tr -d ':\n'
```

> Compose 的 `env_file` 会做变量插值，哈希里的 `$` 需要写成 `$$`；如果嫌麻烦，首次部署先用 `FA_AUTH_PASSWORD`，跑通后再换哈希。

## 4. 本地演练（不需要 Docker）

本机已有 JDK 17/Maven 时：

```powershell
cd F:\JAVA__PROJECT\talk-to-textbook
mvn -q -DskipTests package

$env:FA_AUTH_USERNAME='pengge'
$env:FA_AUTH_PASSWORD='local-test-only'
$env:FA_ADMIN_KEY='local-admin-key'
$env:FA_EMBEDDING_URL='http://127.0.0.1:8090'
java -jar target\talk-to-textbook-0.1.0-SNAPSHOT.jar --server.port=8080
```

1. 打开 `http://127.0.0.1:8080/`：灯下首页公开。
2. 点“开始学习” → 跳到 `/login.html`。
3. 用 `pengge / local-test-only` 登录 → 进入 `/study.html`。
4. 未登录直接访问 `/api/memory` 应返回 401。

本地 embedding：先运行 `start-embedding.bat`（模型在 `F:\opc-tools\llm-models\bge-m3-Q8_0.gguf`）。`/api/health` 里 `embedding.status` 应为 `UP`。

## 5. Docker 部署

### 5.1 准备模型（本地 embedding）

```bash
mkdir -p models
# 把 bge-m3 的 GGUF 放到 models/bge-m3-Q8_0.gguf
# 文件名不同就改 .env 里的 LLAMA_MODEL
```

### 5.2 准备目录权限

容器内应用用户 UID/GID 是 `10001`。bind mount 的目录要让它可以写：

```bash
sudo chown -R 10001:10001 data knowledge sessions logs dict models
```

### 5.3 启动

```bash
cp .env.example .env
# 编辑 .env：登录凭据、模型密钥、embedding 地址
docker compose up -d --build
docker compose ps
curl -fsS http://127.0.0.1:8080/api/health
```

- `app` 只绑定 `127.0.0.1:8080`，公网不能直连。
- 本地 embedding 容器在 compose 内网 `embedding:8090`。
- 更新镜像：`docker compose build app && docker compose up -d app`；数据卷不受影响。
- 停：`docker compose down`（不要加 `-v`，否则会删命名卷；本项目用 bind mount，仍建议别加）。

### 5.4 Nginx / HTTPS

```bash
docker compose --profile proxy up -d
```

把证书放到 `deploy/certs/fullchain.pem`、`privkey.pem`，在 `deploy/nginx.conf` 里取消 HTTPS server 注释，并把 `FA_COOKIE_SECURE=true`。

Nginx 已配置：`proxy_buffering off`、`proxy_read_timeout 300s`、`client_max_body_size 120m`、转发 `X-Forwarded-Proto`。

### 5.5 日志 / 健康 / 回滚

```bash
docker compose logs -f app
curl -fsS http://127.0.0.1:8080/api/health
docker compose restart app
```

回滚：

```bash
# 代码回滚
git checkout <上一个稳定 commit>
docker compose build app
docker compose up -d app
# 数据回滚：先停 app，再用备份恢复到隔离目录，确认后同步回 data/knowledge/sessions
```

## 6. 数据持久化与备份

### 6.1 哪些必须备份

| 数据 | 位置 | 说明 |
|---|---|---|
| 教材知识库 | `knowledge/base.json` | 重建要重新 OCR/提炼，必须备份 |
| 向量缓存 | `knowledge/base.vectors.json` | 可重算，但很贵；建议备份 |
| 会话 | `data/conversations/` | 聊天记录，必须 |
| 长期记忆/核心档案/冲突 | `data/memory/` | 必须 |
| 笔记 | `data/notes/` | 必须 |
| 上传文件 | `data/uploads/` | 必须（原教材/图片） |
| 灌库断点 | `data/checkpoints/` | 可重跑，建议 |
| 会话索引 | `sessions/` | 必须 |
| 词典 | `dict/stardict.db` | 可重新下载；默认不备份，`INCLUDE_DICT=1` 可带上 |
| 日志 | `logs/` | 排障用，可不进备份 |
| 模型文件 | `models/` | 可重新下载，不进备份 |

### 6.2 备份 / 恢复（Linux）

```bash
./scripts/backup.sh
./scripts/restore.sh backups/talk-to-textbook-YYYYmmdd-HHMMSS.tar.gz /tmp/restore-test
# 检查 /tmp/restore-test/knowledge/base.json、data/conversations、data/memory 等
```

`restore.sh` 默认**拒绝覆盖非空目录**；真实恢复要先停服务，确认后再同步。

### 6.3 Windows 本地演练

```powershell
.\scripts\backup.ps1
.\scripts\restore.ps1 -Archive .\backups\talk-to-textbook-....tar.gz -Target .\tmp\restore-test
```

## 7. 资源边界

| 边界 | 默认 | 变量 |
|---|---|---|
| SSE 工作线程 | 4 | `FA_STREAM_WORKERS` |
| SSE 排队 | 8 | `FA_STREAM_QUEUE` |
| 同时调模型的整轮问答 | 2 | `FA_MODEL_MAX_CONCURRENT` |
| 每会话/IP 每分钟问答 | 12 | `FA_RATE_LIMIT_PER_MINUTE` |
| 单文件上传 | 100 MB | `FA_MAX_FILE_SIZE` |
| 整请求上传 | 120 MB | `FA_MAX_REQUEST_SIZE` |
| 图片张数 | 10 | 代码常量 `AgentController.MAX_IMAGES` |
| Agent 步数 | 6 | 代码常量 `AgentService.MAX_STEPS` |
| 工具结果回灌 | 6000 字符 | `FA_TOOL_RESULT_MAX_CHARS` |
| 上下文预算 | 14000 字符 / 预留 3000 | `FA_CONTEXT_MAX_CHARS` / `FA_CONTEXT_RESERVE_CHARS` |

超限反馈：429（限流）、503/400（模型忙/并发满）、SSE `error` 事件（流内失败）。

日志带 `X-Request-Id`，可用它串起一次请求的模型调用、工具执行和错误；日志不打印密钥/完整 prompt。

## 8. 向量服务：两套可执行方案

### A. 本地 llama.cpp（默认，compose 已带）

- 镜像：`ghcr.io/ggml-org/llama.cpp:server`（若拉不到，换 `ghcr.io/ggerganov/llama.cpp:server`）。
- 模型：`bge-m3-Q8_0.gguf`，放 `models/`。
- 端点：容器内 `http://embedding:8090`，接口 `/v1/embeddings`，健康 `/health`。
- 内存：约 1.5–2.5 GB；CPU 4 线程起步。
- 费用：服务器成本；无按次调用费。

### B. 云端 OpenAI 兼容 embedding

`.env`：

```dotenv
FA_EMBEDDING_URL=https://你的服务地址
FA_EMBEDDING_MODEL=你的模型标识
FA_EMBEDDING_API_KEY=你的key
FA_EMBEDDING_HEALTH_PATH=/health   # 没有就留空，健康显示 UNKNOWN
```

要求：请求 `POST /v1/embeddings`、响应 `data[].embedding`；超时/鉴权/条数要核对。换模型/维度后旧向量自动作废，首次检索会重算，别把旧缓存和新模型混用。

## 9. 已知限制

1. 单用户，不是多租户；没有注册/找回密码。
2. 登录会话在内存，重启后需要重新登录（数据不丢）。
3. SSE 取消只能做到“每一步之间停手”；已经开始的一次模型 HTTP 请求无法强杀，这是当前客户端能力边界。
4. 限流/会话计数在单机内存；多实例部署要换共享存储。
5. 文件存储单机；没有数据库/HA。
6. Docker 镜像与云服务器尚未实测（本机无 Docker）；需要目标机器信息后才能完成云端验收。
7. `FA_AUTH_PASSWORD` 明文只适合本地/首次部署；正式环境用 BCrypt 哈希。
