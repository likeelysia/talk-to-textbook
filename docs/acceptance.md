# 第一阶段返工验收记录（本地演练）

日期：2026-10-24（第二轮返工轮） ｜ 代码基线：`e1e0616` + `f85872f` + 本轮修改 ｜ 环境：Windows 11、JDK 17 兼容运行（测试 JVM 25）、本地 llama.cpp 8090

> 重要：**尚未完成 Docker 构建和云端实测**。本文件只记录本机可复现的证据；Docker 未安装，目标服务器/域名/证书未提供。

## 1. 自动测试

```text
mvn -q test
TOTAL=420 FAIL=0 ERR=0 SKIP=0
```

本轮新增/修改的重点：

- `DeepSeekStreamTimeoutTest`（12 条）：首 token 后停住、只有心跳、总时限、无 DONE、上游错误、用户取消、正常结束、连续超时复用名额；另加 400/429/503 错误体停住、只有错误响应头、错误正文半行无换行、连续错误超时后正常请求访问到假模型。
- `SecurityAccessTest`（4 条）：页面/API 访问保护、联系页静态资源匿名可访问、连续错误口令被登录入口限流、状态变更缺 CSRF 被拒。
- `LoginAttemptServiceTest`（2 条）：IP/账号维度退避、退避到期恢复、成功登录清除计数。
- `RateLimitInterceptorTest`（2 条）：窗口内超限 429；同一账号换新会话仍被拦。
- `ModelConcurrencyLimiterTest`（3 条）：总并发 ≤2、后台 ≤1、异常归还名额。
- `ModelClientBudgetTest`（1 条）：用阻塞式假 HTTP 模型同时发 4 个真实 `DeepSeekClient.chat` 调用，实测服务端最大并发 ≤2，结束后名额归还。
- `RetrievalEvalTest`：真实 8090 embedding + 真库，未跳过。

## 2. 模型并发预算

### 入口盘点

所有收费模型请求最终都经过两个客户端：

- 文字/摘要/记忆提炼/对账/灌库抽取：`DeepSeekClient.chat` / `chatWithTools` / `chatWithToolsStream` → `post` / `postStream`。
- 图片识别/OCR：`OpenAiCompatibleVisionClient.ask`。

名额加在这两个客户端的 HTTP 调用层，不再包在 `AgentService` 整轮外面；`AgentService` 原来的 Semaphore 已删除，避免整轮和内部调用重复获取同一信号量。

### 预算

- 总并发默认 2（`FA_MODEL_MAX_CONCURRENT`）。
- 后台提炼最多占 1（`FA_MODEL_BACKGROUND_CONCURRENT`），且只占总额度内的 1 个。
- 等名额默认 10 秒（`FA_MODEL_ACQUIRE_TIMEOUT_SECONDS`），等不到抛 `OverloadedException` → HTTP 503 或 SSE `error`。
- 流式请求从发请求一直占到流结束；`try-with-resources` 保证成功/失败/超时/取消都归还。

### 实测

- 阻塞式假模型：4 个并发文字调用，服务端实际最大并发 **2**；释放后 `availableTotal=2`。
- 3 个后台作用域任务，后台实际最大并发 **1**；作用域结束后 `ModelCallContext.isBackground()=false`。
- 人为在持有名额时抛异常，名额恢复为 1/1。

### 流式响应体超时（本轮新增）

- **总时限**：从发出请求算起，默认 300 秒（`FA_STREAM_TOTAL_TIMEOUT_SECONDS`）。
- **空闲时限**：连续没有 `data:` 事件，默认 60 秒（`FA_STREAM_IDLE_TIMEOUT_SECONDS`）；`: ping` 心跳不算活动，故意用“只有心跳”验证过会超时。
- 看门狗在超时后真正 `close()` 响应流，阻塞的读循环退出，外层 `try-with-resources` 再归还模型 Permit；不是提前还名额让后台继续读。
- 用户取消/心跳发现连接断开时，`StreamSink.isCancelled()` 传给真实客户端，读循环在下一个检查点退出；断流超时也会走同一条关闭路径。
- 本地假模型实测 12 个场景，均不调用真实 API：
  - 首 token 后停住 → `空闲超时`，名额恢复；
  - 只有 `: ping` → `空闲超时`；
  - 持续有 data 但总时长超限 → `总时限`；
  - 不发 DONE 但上游正常关闭 → 正常返回；
  - 上游 500 → 业务错误，名额恢复；
  - 用户取消 → 取消错误，名额恢复；
  - 正常 DONE → 正常返回；
  - 连续 3 次超时 → 每次都能重新取得名额；
  - 400/429/503 返回少量错误正文后停住 → 错误体超时，名额恢复；
  - 只发错误响应头、不写正文 → 错误体超时；
  - 错误正文没有换行、只写半行 → 错误体超时；
  - 连续 3 次错误体超时后，第 4 次正常请求真实访问到假模型并完成。

### 非 2xx 错误体保护（本轮新增）

- 非 2xx 分支现在**先创建 `StreamReadGuard` 再读错误体**，不再绕过看门狗。
- 错误体不是 SSE，空闲判断改成“任意一行错误正文”；最多读 20 行 / 4096 字符。
- 超时关闭真实响应流，读循环退出后才释放 Permit；不会提前还名额。
- 429/5xx 的错误体超时仍按 `TransientApiException` 走有上限的重试；400 等业务错误不自动重试；已经向用户输出正文的流不重放。

## 3. 登录入口防暴力尝试

- 过滤器在 Spring Security 登录过滤器之前执行（`@Order(HIGHEST_PRECEDENCE)`），不是 MVC 拦截器。
- 键 = 来源地址 + 账号 + 全局；换新 Cookie 不会重置（测试使用无会话请求）。
- 5 次失败后第 6 次直接 302 到 `/login.html?locked=1`；退避 30 秒起、指数增长、最多 15 分钟，不永久锁死。
- 实测：在 8099 实例上用独立 `X-Forwarded-For: 10.9.9.9` 连续提交 6 次错误口令，前 5 次 `?error`，第 6 次 `?locked=1`。
- 成功登录清除该 IP/账号计数。
- 登录页显示“尝试次数过多，请稍等一会儿再试”。
- 业务问答限流从“会话 ID”改成“账号/IP + 全局”，同一账号重新登录不会得到新额度。
- 代理头：`forward-headers-strategy=native`，只信任 `FA_TRUSTED_PROXY_REGEX` 内的内网代理；公网直连伪造 `X-Forwarded-For` 不作为来源地址。

## 4. CSRF（Session 认证）

- 已恢复 Spring Security CSRF，使用 `CookieCsrfTokenRepository`（`XSRF-TOKEN` Cookie，JS 可读）。
- 登录表单/退出表单带隐藏 `_csrf`；SPA 的 POST/PUT/DELETE、图片上传、SSE POST 带 `X-XSRF-TOKEN`。
- 登录成功后 Spring Security 会轮换 token；前端每次请求重新读 Cookie，因此用的是新 token。
- 实测：
  - 浏览器（Edge CDP）打开登录页：`XSRF-TOKEN` Cookie 存在、隐藏 `_csrf` 已注入；填口令提交后进入 `/study.html`。
  - 登录后不带 token 的 `POST /api/agent` → **403**；带当前 `X-XSRF-TOKEN` → 400（缺模型 Key 的业务错误，说明已过 CSRF）。
  - 带 token 的 SSE POST → 200 `text/event-stream`。
  - 带 token 的退出登录 → 302 `/`；随后 `/api/auth/me` → 401。
  - 无 CSRF 的 `POST /logout` → 403（MockMvc）。

### 背词页 CSRF（本轮新增）

- 新增独立 `vocab-api.js`，不加载聊天页 `app.js`；从 `XSRF-TOKEN` Cookie 读 token，写请求带 `X-XSRF-TOKEN`。
- 首次 Cookie 不存在时向 `/api/auth/csrf` 要一次；登录后 token 轮换由每次请求重新读 Cookie 处理。
- 401：跳 `/login.html?expired=1`，登录页显示“登录已过期，请重新登录”。
- 403：明确提示“安全校验失败，请刷新页面后重新操作”，**不自动重放 POST**（提取/生成 Word 可能已经执行过）。
- 真实浏览器操作（Edge CDP，已登录）：
  - `/vocab.html` 提取文章 → 8 个单词，未收录 0；
  - 切换英英释义 → 按钮文案变化；
  - 筛选 `fox` → 英英列表 1 条，`精确匹配 1 / 8`；
  - 点击“导出分享” → 前端进入成功提示；服务端 `/api/vocab/docx` 独立验证 200，`Content-Type` 为 docx，大小 4668 字节。
  - 无 CSRF 的 `/api/vocab/extract` → 403；错误 token → 403；正确 token → 200（8 个单词）。
- 本机有 `dict/stardict.db`，所以真实提取/导出已执行；如果目标机器缺词典，会按业务错误提示，不能把“缺词典的 400”当作功能通过。

## 5. 联系页规则

选择“联系页公开”，并只放行它真正需要的静态资源：

- 匿名访问 `/contact.html`、`/style.css`、`/study-theme.css`、`/img/wechat.jpg` 均 **200**。
- 业务 API（会话/记忆/笔记等）仍然 401；没有放开数据目录或业务接口。

## 6. 向量服务两种部署方式

### 外部向量（默认/推荐给小机器）

```bash
# .env: FA_EMBEDDING_URL=https://...
docker compose up -d --build
docker compose ps     # 预期只有 app（及可选 nginx），没有 embedding
```

- `docker-compose.yml` 只含 `app` + 可选 `nginx`，不再有无条件 `depends_on: embedding`。
- 不需要 `models/`，不启动本地 llama.cpp。

### 同机本地模型（可选 override）

```bash
docker compose -f docker-compose.yml -f docker-compose.local-embedding.yml up -d --build
docker compose ps     # 预期 app + embedding
```

- `docker-compose.local-embedding.yml` 才添加 embedding 服务、`depends_on` 和 `FA_EMBEDDING_URL=http://embedding:8090`。
- 本地 8090 实测：embedding UP、检索 recall@5=0.85；停掉后 `/api/health` 显示 DOWN、`degraded=true`。
- 两个 Compose 文件只做了 YAML 解析校验（base: app/nginx；override: app/embedding）；**Docker 未安装，未执行 `docker compose up`**。
- 2g app + 3g embedding 是容器 limit，不是 2GB 服务器方案；2GB 请用外部 embedding。

## 7. 一致备份 / 恢复

### 脚本行为

- 默认**拒绝在线归档**：必须提供 `STOP_CMD`/`START_CMD`；停止写入后等待 `STOP_WAIT_SECONDS`（默认 3 秒）再 tar。
- PowerShell 显式检查 `tar`/启动命令的原生退出码；Bash 在 EXIT trap 中检查启动命令。**恢复失败不再算成功**：归档保留，脚本以退出码 6 结束，并同时打印归档错误/恢复错误。
- 生成 `.manifest.txt` 和 `.sha256`；sidecar 只写摘要和归档 basename，搬目录/搬机器后可校验。
- 恢复前直接校验**本次传入的归档**；缺校验文件、格式错误、摘要不符都拒绝；目标非空拒绝；不删除原备份。

### 实测

- 成功备份小样本 → 恢复 4 个文件。
- 备份成功、启动命令返回 7 → 脚本 **exit 6**，归档保留，打印 `ERROR: service restore failed`。
- 停止命令返回 5 → **exit 3**，不执行恢复。
- 缺目录/归档失败 + 启动失败 → **exit 4**，同时打印“归档失败”和“恢复失败”。
- Bash 跨目录：归档+sidecar 复制到新目录、原归档改名后仍能恢复；只篡改新目录中的归档（原归档不变）→ checksum 拒绝，原归档 hash 不变。
- 非空目标恢复 → 拒绝。
- 真实数据：停掉 app 后备份，恢复出 36 个文件；用另一个实例（8096）指向恢复目录启动，登录后读到 3 个会话、1 条记忆、12925 条知识；验证后停止 8096。没有覆盖真实数据。

## 8. Linux 首次部署

- 文档先 `mkdir -p` 所有数据目录，再 `chown -R 10001:10001`；不再只建 `models`。
- `scripts/backup.sh` / `restore.sh` 在 Git 索引中应设为 `100755`；文档同时统一用 `bash scripts/...`，即使执行位丢失也能跑。
- 壳脚本用 Git Bash 实测了成功备份、在线拒绝（exit 2）、缺目录失败（exit 4）、恢复成功；**没有在真实 Linux 主机/Docker 上执行**。
- 缺 `dict/stardict.db`：词典/生词功能不可用，问答/记忆不受影响。
- 缺本地模型：app 仍启动，`/api/health` embedding 为 DOWN，检索降级为关键词；本地模型容器会反复重启，外部方案不受影响。

### 8.1 干净运行目录冷启动 + 写入 + 重启

- 新建空目录，仅创建 `data/`、`knowledge/`、`sessions/`、`logs/`、`dict/`，不放任何教材/模型/历史数据；
- 从该目录启动 jar（端口 8095），登录后创建会话 `clean-start`；
- 停止进程，用同一目录重启，`/api/conversations` 仍能读到 `clean-start`；
- `/api/health` 为 UP，`knowledgeCount=0`（没有知识库但服务可用）。
- 该测试验证的是 jar 的冷启动/写盘/重启，不是 Docker 容器；容器环境仍未实测。

## 9. 部署文件

- `Dockerfile`（Maven 构建 → JRE17 运行，非 root，healthcheck）
- `docker-compose.yml`（app + 可选 nginx；外部向量默认）
- `docker-compose.local-embedding.yml`（可选本地 llama.cpp）
- `.env.example`（无真实凭据）
- `deploy/nginx.conf`（SSE 关 buffering、超时、HTTPS 注释模板）
- `scripts/backup.sh` / `restore.sh` / `backup.ps1` / `restore.ps1`
- `docs/deploy.md`

## 10. 未执行 / 已知限制

- **Docker 未构建**：本机无 Docker；只验证了 Compose YAML 结构和两个文件的服务集合。
- **未上云**：没有服务器/域名/证书，HTTPS 未实测。
- **未跑真实文字/图片问答**：没有模型 API Key；SSE 只验证鉴权、CSRF 和错误事件。
- **外部 embedding 未实测**：只做配置/接口方向核对，没有真实云端地址。
- **SSE 取消**：只能步骤之间停手；已开始的一次模型 HTTP 请求不能强杀。
- **单用户**：没有多租户隔离、注册、找回密码。
- **计数在内存**：登录退避/限流重启清零；多实例需共享存储。
- **Docker 镜像 tag**：`ghcr.io/ggml-org/llama.cpp:server` 需目标机器确认可拉取，备选 `ghcr.io/ggerganov/llama.cpp:server`。
