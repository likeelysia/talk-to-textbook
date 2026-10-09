# Changelog

本项目的所有重要变更都会记录在此文件。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增 / Added
- **多轮上下文(短期记忆)**:模型本身不记得上一句 —— 每轮请求都得重新把“之前聊过什么”喂给它。
  这里分两条线,并且只有这两条线(不把整本历史裸着塞进去):
  - **最近几轮带原文**(`fa.memory.short-term.max-turns` 默认 6 / `max-chars` 默认 6000)
    —— 近处要准:刚才那句“它”指谁,全靠这截;
  - **更早的压成滚动摘要**(存进会话的 `summary`,可作为 system 消息跟着走)—— 远处要省;
    `summarize=false` 就退化成“超窗口直接丢”
  - **窗口以「轮」为界**:每条用户消息开启一轮,它后面跟的答案算这一轮里的 ——
    不会出现“留下了上一句回答、却把它对应的提问挤掉”这种半截上下文
  - **摘要水位 `Conversation.summarizedCount`**:摘要是累积的,已经进过摘要的消息永不重复喂;
    水位只能前进不能后退(推错 = 历史凭空消失)
  - **摘要失败不拖垮问答**:模型挂了/返回空 → 退回“把用户问过什么按原话列出来”的本地兜底摘要,
    水位照样推进 —— 摘要只是省 token 的辅助手段,不该让用户问不出问题
  - 摘要提示词同样外置(`prompts/session-summarize.txt`);`SessionSummarizer` 是接口(测试塞假实现、以后可换本地小模型)
  - 编排收在新 `service/AssistantService` 里(**记忆 → Agent → 落库**),Controller 只管 HTTP、`AgentService` 只管 ReAct 循环;
    以后加长期记忆(批次 C)就插在这一层
  - `AgentService.run(question, history)`:历史插在**系统提示词之后、当前提问之前**;
    只回放 role + 文本,**不重放旧的 `tool_calls`**(工具消息必须和 `tool_call_id` 结果成对,少一条 API 直接报错)
  - 配置:`fa.memory.short-term.{max-turns,max-chars,summarize}`(注意 `short` 是 Java 关键字,配置里叫 `short-term`)
  - 新增测试:**`ShortTermMemoryTest`(11)**、`AssistantServiceTest`(7,真 AgentService + 假模型 + 临时目录)、
    AgentService 历史注入(2)、水位落盘回读 / 老档兼容(2)
- **多会话(对话框)**:会话不再“全应用共用一份”—— 每个会话有独立的 id / 标题 / 时间 / 消息列表,
  **记忆默认互相隔离**;一个会话一个 JSON + 一份 `index.json`(放 `data/conversations/`,可用
  `FA_CONVERSATION_DIR` 改),**列表页只读 index**,消息再多也不拖慢侧栏
  - **默认会话**:启动时自动兜住 `default`(标题「默认对话」),第一次打开就有得聊;
    提问不传 `conversationId` 也落到它头上 → **老前端不用改**
  - 会话数据用 **JSON 而不是 `.bin`**:老的 `SessionStore`(Java 原生序列化)把类结构焊在字节流里,
    不适合“要长期演进、要能被人看懂、将来要搬进数据库”的数据(老的保留不动,CLI 还在用)
  - `ConversationStore` 把「**读 → 改 → 写**」整个包在一把锁里(Web 是多线程的,两个标签页同时发消息
    不会互相覆盖);会话 id 走白名单正则,**顺手挡住 `../` 路径穿越**;某个会话文件坏了只跳过它、列表不崩;
    `index.json` 被删掉会**扫盘自动重建**
  - `ConversationService` 收业务规则:默认会话必须有、标题不能空、默认会话不许删、空白 id 一律当默认会话、
    **首条提问自动变成标题**(侧栏里一眼认出这个框聊的是什么)
  - REST:`GET/POST /api/conversations`、`GET/PATCH/DELETE /api/conversations/{id}`;
    `POST /api/agent` 请求体加 `conversationId`(json 与 multipart 都支持),响应里带回 `conversationId`
  - **先验会话、再调模型**:`conversationId` 不存在直接 400,不会白烧一趟 token
    (多图链路上尤其重要 —— 识别 10 张图要两分钟)
  - 记进历史的是**用户原本那句话**(多图链路上是“(`N` 张图片)”),不是拼给模型的那段 OCR 长文
  - 前端:左侧「对话」栏 —— 新建 / 切换 / 重命名 / 删除,当前会话存 `localStorage` 刷新不丢;
    标题用 `textContent` 渲染(把用户输入当 HTML 解析就是 XSS 面)
  - 会话记录里带 `scopeId`(**记忆域**)字段:默认等于会话自己的 id,为下一批「多会话共享记忆」留好接口
  - ⚠️ 消息里那对布尔帮手叫 `fromUser()` / `fromAssistant()`,**不是** `isUser()` / `isAssistant()` ——
    Jackson 会把 `isXxx()` 当成 JSON 属性,多写出一个 `"user":true`,回读时又变成“未知字段”直接反序列化失败
    (已踩,并留了「严格 mapper 回读」的回归测试盯着)
- **OCR 引擎可插拔(本地 RapidOCR / 远程 Kimi 视觉)**:把「图 → 文」抽成 `ocr/OcrEngine` 接口 ——
  `KimiOcrEngine`(远程多模态,准、**能把公式读成 LaTeX**,但慢且有组织级 3 RPM 限流)、
  `RapidOcrEngine`(本地 PP-OCRv4 + onnxruntime,**纯 Java、CPU、离线、几百毫秒~几秒一页**);
  由 `OcrEngines` 按 `fa.ocr.engine=kimi|rapid` 选,`PdfReader` 不再直接依赖视觉客户端
  - **本地优先 + 差页回退**:主引擎是 `rapid` 时,读出结果先「体检」(有效字 < `fa.ocr.quality.min-chars`
    或中文占比 < `fa.ocr.quality.min-chinese-ratio`)—— 可疑或直接失败就**换 Kimi 把这一页重读**,
    好页不浪费配额、糊页不凑合(`fa.ocr.fallback-to-kimi=false` 可关)
  - 依赖(`pom.xml`):`io.github.mymonstercat:rapidocr` + `rapidocr-onnx-platform`(按系统自动带
    native 库 + 模型,首次调用时才解压加载,不拖慢启动);native 库/模型解到 `java.io.tmpdir/ocrJava`
  - 页间停顿从 `PdfReader` 挪进 `KimiOcrEngine` 并变成配置 `fa.ocr.kimi.page-gap-ms`
    (撞 429 组织级限流时调大即可,**不用改代码**)
  - **空白页不算失败**:OCR 读出来是空的(封面 / 白纸 / 只剩扫描水印的篇扉页)会先拿页图复核 ——
    墨迹低于 2% 就认定「这页本来就没字」,记一条 info 跳过;真有东西却没读出来(或调用失败)才算「漏页」。
    这样漏页清单里只会剩**真问题**,不会被十几张白纸长期占着、每轮白重试一遍
  - **OCR 文本去噪**:结果里只要有一行含 `fa.ocr.watermark-lines`(默认 `夸克扫描王,极速扫描,就是高效`)
    的关键字,整行丢掉 —— 影印件常见的扫描 App 水印不该变成知识点(留空 = 关闭)
  - 页间停顿的语义改成「**两次调用的开始时刻至少相隔 N 毫秒**」(不是「调完再睡固定时长」):
    无论这页读得快还是慢,节拍都不会失控 —— 兑 429 靠的是「间隔」不是「睡眠」
  - `RapidOcrEngine` 加 `@ConditionalOnProperty(fa.ocr.engine=rapid)`:用 kimi 时根本不创建本地引擎,
    手工 `java -cp` 跑 CLI 也不必把 onnx 那堆依赖拉进 classpath
  - 新增测试:**`OcrQualityTest`(6)**、`OcrEnginesTest`(12)、`OcrTextFilterTest`(7)、
    `RapidOcrEngineTest`(3,真加载本地引擎画图认字)
- **提炼结果容错解析**:`KnowledgeExtractor` 从「只认整段 JSON 数组」升级成五级降级 ——
  ①整段数组 ②剥壳后截最外层 `[...]`(兼容 `{"points":[...]}`)③多个对象**并排**吐出(模型忘了包数组)→
  按顶层花括号逐个捞(认字符串与转义,`detail` 里带 `{a, b}` 也不会切错)④数组被塞进字符串
  (`{"type":"json_object","content":"[...]"}`)→ 剥壳再解 ⑤实在不行才抛异常。
  真机灌《大学物理学》时前四种都出现过 —— 旧逻辑一丢就是整块 2~4 个知识点
- **灌库断点(每 40 页落一次盘)**:新增 `CheckpointIngestService` —— 教材按「块」灌(默认 40 页一块),
  每块「提炼完 → 落库 → 记进度」。中途断网 / 关窗口 / 限流打嗝,**重跑同一条命令就从上一个完成的块续**,
  已灌过的页不会重新 OCR、不会重新提炼。配套改动:
  - `DocumentReader` 增 `read(file, fromPage, toPage)` / `pageCount(file)`(默认实现忽略范围,txt/md 不受影响);
    `PdfReader` 真正实现按页读(**OCR 也只跑请求的那几页**),`DocPipeline.load(file, from, to)`
  - 新增 `IngestProgress`(进度记录):文件名取「来源 + 文件名 + 大小」的 SHA-1 → **纯 ASCII**,
    避开中文书名的编码坑;**页数或文件大小变了旧断点自动作废**,灌完再跑直接跳过
  - 配置:`fa.extract.block-size`(`FA_INGEST_BLOCK_SIZE`,默认 `40`)、
    `fa.checkpoint.dir`(`FA_CHECKPOINT_DIR`,默认 `data/checkpoints`)
  - CLI:`IngestMain --progress` 看断点、`--restart` 忽略旧断点重来,并支持一次传多个文件
  - 步骤顺序固定「**先落库、再记断点**」——中间挂了会重做该块,而"同 id 覆盖"让重做是**幂等**的(反过来会丢数据)
- **灌库漏页兜底(失败页追踪 + 续跑补灌)**:OCR 单页失败(限流 429 / 图太糊)**不再静默丢页** ——
  失败的页号记进断点,下次跑同一条命令时**先补灌这几页**,补上就划掉、补不上就留着(不阻塞、不死循环)。
  配套改动:
  - 新增 `ReadResult`(文本段 + 失败页号)与 `DocPipeline.LoadedDoc`;`DocumentReader.readReport(file,from,to)`
    默认实现 = `read(...)` + 空失败表,`PdfReader` 覆盖它并把 OCR 失败页号带出来
  - `IngestProgress` 新增 `failedPages`(升序去重;旧断点文件没这个字段时反序列化成 `null` → **构造器兜成空表**),
    `afterBlock(...)` 多收一个失败页清单;
  - `CheckpointIngestService`:`runBlock` 同时回收两类漏页 —— ① OCR 失败页(读取器上报)
    ② **提炼失败块所在的页**(从出处 `第 N 页` 解析);补灌 pass 放在**每轮开跑前**,逐页重灌、每页落盘;
    "整本跑完但仍有漏页"不再算「已完成」(新增 `completedCleanly()`),`--progress` 会标「页已跑完(有漏页)」并列出页号
  - CLI:`IngestMain --progress` / 导入结束都会显示没灌成功的页号,并提示"重跑即补灌"
- **知识库的“删 / 换”能力(按来源)**:
  - `KnowledgeStore` 接口新增 `sources()` / `countsBySource()` / `removeBySource(source)` / `replaceSource(source, items)`;
    JSON 实现同时会**清掉孤立向量**(删条目的同时 sidecar 不残留);来源为空时**拒绝执行**(防手滑清库)
  - `SourceResolver`:用户手打的来源名 → 真实来源名的解析。**全名优先 → 部分匹配必须唯一命中**,
    歧义/未命中都**拒绝并列出候选** —— 模糊匹配 + 直接删库 = 事故,所以宁可拒绝
  - CLI:`IngestMain --list` / `--remove-source <来源名>` / `--replace <教材文件>`
  - REST:`DELETE /api/knowledge/source?name=...`(受 `X-Admin-Key` 保护)、`POST /api/ingest?replace=true`;
    `GET /api/knowledge` 增加 `sourceCounts`
  - 为什么删也要走接口:应用在跑时知识库全在内存里,直接改磁盘文件会被它下一次写盘盖回去
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
- 单元测试 **105 → 117**(新增:按来源删除 / 重灌 / 来源解析 / 只算孤立的向量清理)
- 单元测试 **117 → 130**(新增:断点续做 / 按页读 PDF / 块级失败与版本失效)
- 单元测试 **130 → 134**(新增:漏页落盘与续跑补灌 / 补灌再失败不阻塞 / 提炼失败块也记页号 / 旧断点无新字段兼容)
- 单元测试 **134 → 153**(新增:OCR 结果体检规则 / 引擎选择与回退 / 本地 RapidOCR 真加载认字)
- 单元测试 **153 → 162**(新增:OCR 文本去噪(水印行) / 水印行先剔再体检 / 整页只有水印判为空白)
- 单元测试 **162 → 170**(新增:提炼容错解析(并排对象 / 套壳 / 字符串壳 / 花括号 / 单对象 / 空名字) / 空白块不算失败)
- 单元测试 **170 → 200**(新增:会话落盘 / 索引重建 / 路径穿越 / 坏文件容错 / 业务规则 / 首条提问当标题 / REST 翻译 / 严格 mapper 回读)
- 单元测试 **200 → 225**(新增:窗口裁剪(轮边界 / 字符预算 / 至少留一轮 / 空气泡) / 摘要注入与去重 / 摘要失败兜底 / 编排(历史落点 / 摘要时机 / 坏会话不烧 token) / 水位落盘)

### 变更 / Changed
- **入库的“来源标识”与“落盘文件名”解耦**:`IngestService` 新增 `ingest(file, source, replace)`;
  上传接口用 **原始文件名**当来源(落盘名带时间戳、每次都不一样,拿它当来源的话同一本书重传就变成另一本书,
  既覆盖不掉旧的、重灌也找不到旧的)
- **`IngestMain` 改造成 Boot 命令行**(`WebApplicationType.NONE`,不起 Tomcat):
  才能读到 `application.yml` / `FA_*` 环境变量(详见“修复”)
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
- **`IngestMain` 静默失效(NPE)**:它以前用裸 `AnnotationConfigApplicationContext` 起容器,
  **读不到 `application.yml`** → `AppConfig` 全字段为 null → `DeepSeekClient` 构造就 NPE、整条 CLI 全挂
  (改成 `@ConfigurationProperties` 之后引入的回归);现改为 Boot 命令行(`--list` / `--remove-source` / `--replace` 实测可用)
- **`.gitignore` 的 `knowledge/` 误伤源码目录**:裸写 `knowledge/` 会连
  `src/main/java/.../formalagent/knowledge/` 一起忽略 —— 新加的源码文件根本进不了仓库
  (`JsonKnowledgeStoreTest.java` 就一直没被跟踪);改为 `/knowledge/`(锚定仓库根),数据仍不入库
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
