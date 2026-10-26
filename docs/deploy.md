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

### 1.1 发布产物（2026-10-24）

- 发布提交：`7829850`（含背词/单词手记收口）；工作树另含验收文档更新。
- 构建基线：Temurin JDK **17.0.20.1**，`mvn clean package -DskipTests`。
- JAR：`dist/talk-to-textbook-0.1.0-7829850.jar`
- SHA-256：`61C7793C3EA5476BF15923B913D36C14B742844731EA8959E770F15BA6B5144D`
- 干净检出测试：**439 通过 / 0 失败 / 0 错误 / 1 跳过**；跳过项是 `RetrievalEvalTest`（干净检出没有 `knowledge/base.json`，不是代码失败）。
- 当前本机**没有 Docker、没有真实模型 API Key、没有云服务器/域名/证书**；因此 Docker/云端/真实模型未执行，不得把本文命令写成已验证。
- 回滚 JAR：`dist/talk-to-textbook-0.1.0-24a4bb4.jar`，SHA-256 `BBD86B1B8F8124E6E08298B19125F1F245427020800E0DCE6F500FD4651F89BC`；回滚前先停写并备份数据，新版本生词本数据需一起恢复到升级前备份。

### 1.2 知识库流式优化包（2026-10-25，已在服务器启用）

- 提交：`2eb600a perf(knowledge): JSON 知识库改为流式读写`
- JAR：`dist/talk-to-textbook-0.1.0-2eb600a.jar`
- SHA-256：`72A3473AC3523B10B94E0E9E0433221B02D6F2B50BCBA1FA6138126A2F776CD4`
- 本地同一份 7287 条数据、`-Xmx768m`：
  - 旧 `7829850`：启动峰值 1118.5 MB，稳定 936.1 MB
  - 新 `2eb600a`：启动峰值 449.3 MB，稳定 360.7 MB
- 服务器实际切换后：`active (running)`，`knowledgeCount=7287`，`MemoryCurrent=355.1 MB`，`MemoryPeak=355.3 MB`。
- 旧版仍保留在 `/opt/lamplit/releases/7829850/`，回滚只需切回链接并重启。
- 测试：440 通过 / 0 失败 / 1 跳过。
- 详细对照与回滚见 `docs/knowledge-streaming.md`。

### 1.3 10 万点检索减负包（2026-10-26，已在服务器启用）

- 提交：`0709e5e fix(knowledge): 缺向量按条回退并限制补齐并发`
- JAR：`dist/talk-to-textbook-0.1.0-0709e5e.jar`
- SHA-256：`989044C83B8A943158D399DE65A8571973D554F2D854A8E0EC47A177524861BA`
- 查询路径不再自动补齐向量；新增 `count/page/item`、`vectors/status`、`vectors/backfill`；Top-K 改为有界堆。
- 缺向量条目按单条关键词回退，默认 `min-score=0.45` 下不会因只拿到 0.3 分被误杀；补齐并发第二个请求返回 409。
- 补齐落盘改为临时文件+原子替换，失败后重试不会误报完成；慢 embedding 不持有查询锁，提交前校验条目未被删除/替换。
- 本地 `-Xmx768m` 合成数据：10k 稳定 380.9–527.5 MB、p50 13 ms；30k 530.3–541.7 MB、p50 38–43 ms；100k 889.0–1018.1 MB、p50 124–167 ms（仅存储层检索，不含 embedding/LLM；堆余量本轮未测，详见 `docs/knowledge-scale.md`）。
- 真实教材回归：recall@5 0.85、MRR 0.77、页命中 0.88、拒答 1.00；测试 450 通过 / 0 失败 / 0 跳过。
- 补齐写放大实测：10k/1000 约累计写 226 MB；10k/100 约写 2.08 GB；30k/1000 约写 1.91 GB；100k/1000 估算约 20.8 GB（未实跑）。
- **服务器已于 2026-10-26 切换到该版本**：`/opt/lamplit/releases/0709e5e`，健康 200、7287 条、`missingVectors=0`、服务内存约 327–335 MB；旧版 `2eb600a` 保留可回滚。
- 现场结果：[Lamplit服务器升级现场结果-2026-10-26.md](C:/Users/29458/Desktop/GPT的指导建议/Lamplit服务器升级现场结果-2026-10-26.md)。生产项目口令未提供；隔离副本只完成既有数据读取和一次受限问答，**新增写入未验**，不能写成完整功能验收通过。

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
| `FA_STREAM_IDLE_TIMEOUT_SECONDS` | 流式响应空闲时限（只看 data） | 默认 60 |
| `FA_STREAM_TOTAL_TIMEOUT_SECONDS` | 流式响应总时限 | 默认 300 |
| `FA_MODEL_MAX_CONCURRENT` | 所有模型调用总并发 | 建议 2 |
| `FA_MODEL_BACKGROUND_CONCURRENT` | 后台提炼最多占用 | 建议 1 |
| `FA_MODEL_ACQUIRE_TIMEOUT_SECONDS` | 等模型名额上限 | 默认 10 |
| `FA_RATE_LIMIT_PER_MINUTE` | 每账号/IP 每分钟问答 | 默认 12 |
| `FA_TRUSTED_PROXY_REGEX` | 只信任这些内网代理的转发头 | 默认内网网段 |
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

### 5.1 准备模型（仅本地方案）

```bash
mkdir -p models
# 把 bge-m3 的 GGUF 放到 models/bge-m3-Q8_0.gguf
# 文件名不同就改 .env 里的 LLAMA_MODEL
```

### 5.2 准备目录权限

**先建目录，再 chown**（干净检出时这些目录不存在；Docker 不会替你修好 bind mount 的主机权限）：

```bash
mkdir -p data/conversations data/memory data/notes data/uploads data/checkpoints \
         knowledge sessions logs dict models
# models 只有本地方案会用到;外部方案留空即可
sudo chown -R 10001:10001 data knowledge sessions logs dict models
```

容器内应用用户 UID/GID 是 `10001`；不要用 root 跑应用。

### 5.3 启动（两种向量方案二选一）

先：

```bash
cp .env.example .env
# 编辑 .env：登录凭据、模型密钥、FA_EMBEDDING_URL
```

**A. 外部向量服务（不启动本地 llama.cpp）**

```bash
docker compose up -d --build
docker compose ps          # 应只有 app（以及你要的 nginx），没有 embedding
curl -fsS http://127.0.0.1:8080/api/health
```

`.env` 里 `FA_EMBEDDING_URL` 填服务根地址（客户端会拼 `/v1/embeddings`），并确认 `FA_EMBEDDING_API_KEY`/模型名；外部方案不需要 `models/` 目录。

**B. 同机本地模型（llama.cpp 容器）**

```bash
# 先把 bge-m3 GGUF 放到 models/bge-m3-Q8_0.gguf（文件名不同改 LLAMA_MODEL）
docker compose -f docker-compose.yml -f docker-compose.local-embedding.yml up -d --build
docker compose ps          # 此时应有 app + embedding
curl -fsS http://127.0.0.1:8080/api/health
```

- `app` 只绑定 `127.0.0.1:8080`，公网不能直连。
- 本地方案才把 `FA_EMBEDDING_URL` 覆盖成 `http://embedding:8090`；外部方案不要带 local override。
- **内存提示**：app 2g + embedding 3g 是两个容器各自的 limit，不是“2GB 服务器方案”。2C4G 建议只用外部 embedding；本机模型建议 4C8G 起，并按实测调 `APP_MEM_LIMIT`/`EMBEDDING_MEM_LIMIT`。
- 更新镜像：`docker compose build app && docker compose up -d app`；本地模型方案要同时保留 `-f` 参数。
- 停：`docker compose down`（别加 `-v`）。

### 5.4 Nginx / HTTPS

```bash
docker compose --profile proxy up -d
# 本地模型方案:在前面加 -f docker-compose.local-embedding.yml
```

把证书放到 `deploy/certs/fullchain.pem`、`privkey.pem`，在 `deploy/nginx.conf` 里取消 HTTPS server 注释，并把 `FA_COOKIE_SECURE=true`。

Nginx 已配置：`proxy_buffering off`、`proxy_read_timeout 300s`、`client_max_body_size 120m`、转发 `X-Forwarded-Proto`。

### 5.5 日志 / 健康 / 回滚

```bash
# 本地模型方案把下面每条 docker compose 都加上
#   -f docker-compose.yml -f docker-compose.local-embedding.yml
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
# 本地模型方案上面两条同样加 -f docker-compose.local-embedding.yml
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

### 6.2 备份 / 恢复（Linux，一致备份）

备份默认要求**先停服务/停写**，避免会话、记忆水位和提炼结果来自不同时刻：

```bash
STOP_CMD='docker compose stop app' START_CMD='docker compose start app' \
  bash scripts/backup.sh

# 恢复前先校验 checksum；目标必须为空目录
bash scripts/restore.sh backups/talk-to-textbook-YYYYmmdd-HHMMSS-*.tar.gz /tmp/restore-test
# 检查 /tmp/restore-test/knowledge/base.json、data/conversations、data/memory 等
```

- 脚本会生成 `.manifest.txt` 和 `.sha256`；恢复前必须校验通过。sidecar 只记录摘要和归档 basename，归档+校验文件一起搬到别的目录/机器仍可恢复。
- 归档失败、校验失败、目标非空都会非零退出；服务恢复命令在 `trap` 里执行。
- **服务恢复失败不再算成功**：归档已经生成也会保留，但脚本会以 6 退出，并同时打印“归档错误/恢复错误”两类信息（如果有）。按提示手动检查服务。
- 真实恢复要先停服务，确认隔离目录内容后再同步回项目；不要直接覆盖真实数据。
- 没有 `STOP_CMD`/`START_CMD` 时脚本**拒绝归档**，不再做“运行中直接 tar”。
- 个人版只有一个 app 写入实例；如果同时开了 CLI 灌库/导入，也要一并停掉。`STOP_WAIT_SECONDS`（默认 3 秒）给优雅停机和后台任务收尾留时间。
- 不想依赖执行位时统一用 `bash scripts/backup.sh` / `bash scripts/restore.sh`。

### 6.3 Windows 本地演练

```powershell
.\scripts\backup.ps1 -StopCommand "Write-Output stop" -StartCommand "Write-Output start"
.\scripts\restore.ps1 -Archive .\backups\talk-to-textbook-....tar.gz -Target .\tmp\restore-test
```

PowerShell 备份同样要求停写命令，并显式检查 `tar` 的原生命令退出码；恢复会校验 `.sha256`。启动命令抛异常或返回非零时，归档保留但脚本非零退出；归档和恢复同时失败时两类错误都会打印。

## 7. 资源边界

| 边界 | 默认 | 变量 |
|---|---|---|
| SSE 工作线程 | 4 | `FA_STREAM_WORKERS` |
| SSE 排队 | 8 | `FA_STREAM_QUEUE` |
| 流式响应总时限 | 300 秒 | `FA_STREAM_TOTAL_TIMEOUT_SECONDS` |
| 流式响应空闲时限 | 60 秒（只看 `data:`，心跳不算） | `FA_STREAM_IDLE_TIMEOUT_SECONDS` |
| 所有文字/视觉/摘要/提炼合计并发 | 2 | `FA_MODEL_MAX_CONCURRENT` |
| 后台提炼最多占用 | 1（总预算内） | `FA_MODEL_BACKGROUND_CONCURRENT` |
| 等模型名额上限 | 10 秒 | `FA_MODEL_ACQUIRE_TIMEOUT_SECONDS` |
| 每账号/IP 每分钟问答 | 12（另加全局预算） | `FA_RATE_LIMIT_PER_MINUTE` |
| 登录失败退避 | 5 次/5 分钟，30 秒起，最多 15 分钟 | 代码常量 |
| 单文件上传 | 100 MB | `FA_MAX_FILE_SIZE` |
| 整请求上传 | 120 MB | `FA_MAX_REQUEST_SIZE` |
| 图片张数 | 10 | 代码常量 `AgentController.MAX_IMAGES` |
| Agent 步数 | 6 | 代码常量 `AgentService.MAX_STEPS` |
| 工具结果回灌 | 6000 字符 | `FA_TOOL_RESULT_MAX_CHARS` |
| 上下文预算 | 14000 字符 / 预留 3000 | `FA_CONTEXT_MAX_CHARS` / `FA_CONTEXT_RESERVE_CHARS` |

超限反馈：429（问答/登录限流）、503（模型名额等不到）、400（参数/模型业务错）、SSE `error` 事件（流内失败）。

模型并发预算放在**真实 HTTP 调用层**（`DeepSeekClient.post/postStream`、`OpenAiCompatibleVisionClient.ask`），不是包在某一轮问答外面；因此文字、图片、滚动摘要、记忆提炼/对账共用同一份名额，流式请求一直占到流结束。后台提炼只占 `FA_MODEL_BACKGROUND_CONCURRENT`，不会把交互全挤掉；异常/超时/取消都由 try-with-resources 归还。

流式响应体另有看门狗：总时限从发请求算起，空闲时限只看 `data:` 事件（`: ping` 心跳不算）。任一超时都会关闭响应流、结束读循环并释放模型名额；不会出现“名额先还了、后台还在读”。用户取消/心跳发现连接断开时，读取循环会在下一个检查点停手。

**非 2xx 错误响应体也受同一套时限保护**：按字节最多读 4096 字节（固定 1024 字节缓冲），不依赖换行、完整正文或 EOF；达到上限立即停止并关闭响应体，UTF-8 截断用替换字符处理。错误体不是 SSE，空闲判断是“任意一段错误正文”，只发响应头不写正文、或写半段后停住都会超时。429/5xx 的错误体超时仍按可重试异常处理，重试次数有上限；400 等业务错误不自动重试。

CSRF：Session 认证下默认开启 Spring Security CSRF；登录表单/退出表单带 `_csrf` 隐藏字段，SPA 的 POST/PUT/DELETE/上传/SSE 带 `X-XSRF-TOKEN` 头（Cookie `XSRF-TOKEN`）。缺失/错误凭证返回 403。

联系页：`contact.html` 公开，同时放行它需要的 `/style.css`、`/study-theme.css`、`/img/wechat.jpg`；业务 API 和数据目录仍受登录保护。

日志带 `X-Request-Id`，可用它串起一次请求的模型调用、工具执行和错误；日志不打印密钥/完整 prompt。

## 8. 向量服务：两套可执行方案

### A. 同机本地 llama.cpp（可选 override）

```bash
docker compose -f docker-compose.yml -f docker-compose.local-embedding.yml up -d --build
docker compose ps     # 应有 app + embedding
```

- 镜像：`ghcr.io/ggml-org/llama.cpp:server`（拉不到就换 `ghcr.io/ggerganov/llama.cpp:server`）。
- 模型：`bge-m3-Q8_0.gguf`，放 `models/`；缺模型时 embedding 容器会反复失败，app 仍能启动但 `/api/health` 显示 embedding DOWN，语义检索降级，文字问答/记忆仍可用（RAG 只剩关键词）。
- 端点：容器内 `http://embedding:8090`，接口 `/v1/embeddings`，健康 `/health`。
- 内存：约 1.5–2.5 GB；CPU 4 线程起步。2GB 机器不要用这套。

### B. 外部 OpenAI 兼容 embedding（不启动本地模型）

```bash
# .env 里填好外部地址后
docker compose up -d --build
docker compose ps     # 不应出现 embedding 容器
```

`.env`：

```dotenv
FA_EMBEDDING_URL=https://你的服务根地址   # 客户端会拼 /v1/embeddings
FA_EMBEDDING_MODEL=你的模型标识
FA_EMBEDDING_API_KEY=你的key
FA_EMBEDDING_HEALTH_PATH=/health         # 没有就留空，健康显示 UNKNOWN
FA_EMBEDDING_TIMEOUT_SECONDS=20
```

要求：请求 `POST /v1/embeddings`、响应 `data[].embedding`；超时/鉴权/条数要核对。换模型/维度后旧向量自动作废，首次检索会重算，别把旧缓存和新模型混用。外部方案**不需要 `models/`，也不需要 `docker-compose.local-embedding.yml`**。

## 9. 已知限制

1. 单用户，不是多租户；没有注册/找回密码。
2. 登录会话在内存，重启后需要重新登录（数据不丢）。
3. SSE 取消只能做到“每一步之间停手”；已经开始的一次模型 HTTP 请求无法强杀，这是当前客户端能力边界。
4. 限流/登录失败计数在单机内存；多实例部署要换共享存储。代理 IP 只信任 `FA_TRUSTED_PROXY_REGEX` 里的内网代理。
5. 文件存储单机；没有数据库/HA。
6. Docker 镜像与云服务器尚未实测（本机无 Docker）；需要目标机器信息后才能完成云端验收。
7. `FA_AUTH_PASSWORD` 明文只适合本地/首次部署；正式环境用 BCrypt 哈希。
8. 外部 embedding 只做了配置/接口方向核对，没有真实云端服务可测；本地 llama.cpp 是实测路径。
9. 缺 `dict/stardict.db` 时词典/生词功能不可用，问答不受影响；缺本地模型时 app 仍启动，但 `/api/health` 的 embedding 为 DOWN，检索降级为关键词。
10. app 2g + embedding 3g 是容器 limit，不是“2GB 服务器可跑”的承诺；2GB 机器请用外部 embedding，并按实测压内存。
