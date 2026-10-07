# Changelog

本项目的所有重要变更都会记录在此文件。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增 / Added
- **多图提问**:`POST /api/agent`(multipart)支持一次最多 **10 张**图片(`files` 字段可重复)——
  逐张视觉识别后按 `【第 n 张】` 合并成一段文字交给 Agent;**单张失败只跳过它,不拖垮整批**;
  超过 10 张直接拒绝(服务端兜底,前端也拦)
- **网页前端**:`resources/static/` 下的原生单页界面(无框架、无构建、同源无跨域) ——
  文字提问 / 上传图片提问 / 知识库概览 / 联系开发者页(扫码加微信);回答下方可展开**工具调用轨迹**;
  **一次可选最多 10 张图**:缩略图带 `×` 可逐张删除、可一键清空、实时计数 `n/10`,
  上传前在浏览器里自动压缩(长边 1280 / JPEG 0.85)以加快识别;等待时显示已耗时与预估
- **工具集扩展**:`calculator`(自写**受限表达式求值器**—— 只认数字/运算符/白名单函数与常量,不认变量与任何方法调用,杜绝 `eval` 注入)、
  `unit_convert`(长度/质量/时间/角度/温度,认中英文单位名)
- **混合检索(hybrid)+ 阈值**:向量相似度与关键词命中率加权(`0.7 / 0.3`),
  低于 `fa.knowledge.min-score`(默认 0.35)当作未命中 —— 从源头减少“硬塞不相干内容”
- **Prompt 模板外置**:提示词搬到 `resources/prompts/*.txt` + `prompt.Prompts` 加载器(带缓存、缺失 fail-fast)
- **接口支持图片**:`POST /api/agent`(multipart)上传图片 → 视觉识别 → Agent 回答
- **接口鉴权**:`AdminKeyInterceptor` + `WebConfig`;管理接口需 `X-Admin-Key`
  (从环境变量 `FA_ADMIN_KEY` 读;**未配置 = 关闭并打 WARN**,适合本地开发)
- **Agent 化(工具调用 / ReAct)**:
  - `agent/Tool` 抽象(名字 + 说明 + JSON Schema 参数 + execute);
  - 三个只读工具:`search_knowledge`(语义检索)、`knowledge_overview`(全局概况)、`get_knowledge_page`(按来源+页码精确取);
  - `agent/AgentService`:跑“思考 → 调工具 → 再思考”循环,**带步数上限**与**工具调用轨迹(trace)**;
  - `POST /api/agent`(取代原 `POST /api/qa`);
  - 工具协议 DTO:`ToolCall` / `ToolDefinition` / `AgentMessage`(含 `tool_calls` / `tool_call_id`)/ `AssistantTurn`
- **HTTP 接口(REST)**:`spring-boot-starter-web` + 内嵌 Tomcat;
  `POST /api/qa`(问答)、`POST /api/ingest`(入库)、`GET /api/knowledge`、`GET /api/health`;
  统一异常处理(`@RestControllerAdvice` → 规范的 JSON 错误 + 状态码)
- **日志规范化**:`logback-spring.xml`(控制台 + 应用日志按天滚动 + **单独的模型调用审计日志**);
  `println` → SLF4J;`ApiLogger` 由手写文件追加重写为一行 logger 调用
- **知识库**:`KnowledgeItem` / `KnowledgeStore`(接口) / `JsonKnowledgeStore`(JSON 落盘,换实现不动业务)
- **入库流水线**:`IngestService` + `IngestMain` —— 文档 → 知识点(带页码出处)→ 知识库
- **文档读取抽象化**:`DocumentReader.read` 返回带位置的文本段(`TextSegment`);`ReaderRegistry` 按扩展名分派
- **PDF 读取**:`PdfReader` —— ① 文字层(`PDFTextStripper`);② 无文字层(扫描件)→ 逐页渲染 + 视觉 OCR
- **图片 OCR / 图片问答**:`VisionClient` / `MoonshotVisionClient`(Kimi 视觉)、`ImageReader`、`QaService`(识图 → 检索 → 带出处回答)
- **语义检索(真 RAG 第一步)**:`EmbeddingClient`(接口)/ `LocalEmbeddingClient`(本地 llama.cpp);
  `JsonKnowledgeStore` 检索升级为**余弦相似度 top-K**,向量存 sidecar `knowledge/base.vectors.json`;
  embedding 服务不可用时**自动降级**为关键词匹配
- **Spring Boot 化**:父工程 `spring-boot-starter-parent:3.5.16`、`FormalAgentApplication`、
  `spring-boot-maven-plugin`(**可执行 fat jar**)、`@ConfigurationProperties`、`application.yml` + `dev` profile
- **健壮性**:Spring Retry 声明式**重试 + 退避**;单块提炼 / 单页 OCR 失败不再拖垮整批
- **命令行**:`ChatCli`(`CommandLineRunner`,由 Boot 调起)取代原 `Main`
- 本地 embedding 一键脚本 `start-embedding.bat`
- 单元测试 **54 → 74**(新增向量检索、图片读取、PDF 双策略、配置绑定等)
- 单元测试 **99 → 105**(新增多图上传:张数上限 / 顺序合并 / 单张失败容错 / 全失败兜底)

### 变更 / Changed
- **用户端撤下入库入口**:网页上的「上传教材入库」面板删除,替换为「没有找到教材?**点击这里联系开发者**」
  → 跳转 `/contact.html`(整页只有开发者微信二维码);**入库能力仍在后端**(`POST /api/ingest` 与 `cli.IngestMain`)
- **接口调整**:移除 `POST /api/qa`,由能力更强的 **`POST /api/agent`** 取代(不保留并列入口)
- **默认启动形态**:由“交互式命令行”改为 **HTTP 服务**(8080);CLI 需显式开启 `--fa.cli.enabled=true`
- **配置**:`config.properties` → **`application.yml`**(前缀 `fa.*`);`@Value` → **`@ConfigurationProperties`**
  (`AppConfig` 由散字段类改为 **record + 分组**),支持 profile 与环境变量覆盖
- **入口**:手动 `new AnnotationConfigApplicationContext(...)` → Boot `SpringApplication.run(...)`
- **视觉请求关闭"思考模式"**(`thinking.type = "disabled"`):OCR 场景更快(约 51s → 16s)且输出更稳
- 超时:LLM 请求读取超时提升到 **120s**(视觉 OCR 单页耗时较长);连接超时独立为 `fa.llm.connect-timeout-seconds`

### 安全 / Security
- **修复「任意文件读取 / 路径遍历」**:`POST /api/ingest` 由“传路径”改为 **上传文件**(multipart/form-data);
  新增 `FileStorage`(清洗文件名 + 扩展名白名单 + 固定目录 + 时间戳唯一化 + normalize 后前缀校验),
  并新增 4 条安全单元测试(路径遍历 / 绝对路径 / 白名单 / 落盘)
- 统一异常处理不再把框架异常(415 / 404 / 405 等)压成 500,保留正确状态码

### 修复 / Fixed
- **大文件上传报 HTTP 413**(`Maximum upload size exceeded`)→ `spring.servlet.multipart` 显式配置:
  单文件 `100MB`(`FA_MAX_FILE_SIZE`)、整请求 `120MB`(`FA_MAX_REQUEST_SIZE`)、`file-size-threshold: 1MB`;
  此前用 Boot 默认(1MB / 10MB),灌整本教材必被 Tomcat 拦下
- **Kimi 视觉 `content` 偶发为空**(思考模式下答案全跑进 `reasoning_content`)→ 关闭思考 + 空内容按瞬时故障重试
- **429 限流**(组织并发上限=1)→ 逐页停顿 + 确定性退避(随机退避可能退到 0ms,对限流无效)
- 扫描版 PDF 无文字层导致"没有可提取内容" → 自动回退视觉 OCR
- 非 2xx 响应与缺少 `choices` 时给出明确错误(此前可能解析为空 / NPE)

## [0.1.0] - 2026-09-17

### 新增 / Added
- 初始化 Maven 项目骨架(`groupId: io.github.likeelysia`,`artifactId: formal-agent`)
- 配置 JUnit 5 测试依赖与 `maven-compiler-plugin` / `maven-surefire-plugin`
- 添加 MIT 许可证、README、`.gitignore`(含密钥防护规则)
- 接入 DeepSeek `/chat/completions`(JDK `HttpClient` + Jackson 解析)
- 多轮对话(`ChatSession` / `Message`)、会话存档(`SessionStore`)
- 文档 → 知识点提取流水线(`DocPipeline` → `KnowledgeExtractor` → `KnowledgeReport`)
- 结构化输出(JSON mode)、统一 `ObjectMapper` 单例与强类型 DTO
- 应用配置外置 + Spring IoC 装配 + 按天调用日志(`ApiLogger`)
