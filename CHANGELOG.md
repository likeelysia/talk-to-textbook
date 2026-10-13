# Changelog

本项目的所有重要变更都会记录在此文件。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 修复 / Fixed
- **改了前端却看不到变化(浏览器手里是旧 JS/CSS)**:静态资源一直没设缓存头,浏览器按“启发式新鲜度”自己拿主意,
  于是服务端已经是新版、页面却还是旧版 —— 这次“公式一直显示裸 `$$`”的真凶就是它(渲染代码本身是对的)。
  现在统一加 `Cache-Control: no-cache`(`spring.web.resources.cache.cachecontrol.no-cache`):
  每次请求带 `If-Modified-Since` 找服务端核对一下,**没变就 304(几乎无开销)、变了立刻拿新的**。
- **流式输出的四处遗漏**(反馈“流式没做完美”):
  - **边生成边排版**:以前要等 `done` 才排版,整个过程看的就是裸 `**` / `$$`。现在每 ~150ms 重画一次(节流),
    但**只重画已闭合的内容** —— 没闭合的代码块 / 块级公式先不碰(防 Markdown 把整段排版弄乱),字照常先按纯文本长出来
  - **「停止」/ 断线不再留一屏裸文本**:中途停下也会把已有内容排版好再停
  - **不再“抢滚动条”**:人手动往回翻历史时,不再被新来的 delta 一把拽回最底(只有本来就贴着底部才自动跟)
  - **终稿后补一次滚动到底**
- **渲染变“可选”**:渲染失败(KaTeX 认不得、库没加载上)一律退化成纯文本 —— 以前 `renderAnswer` 一旦抛异常,
  整个回答会被换成一句“出错了”,反而把答案弄丢
- **embedding 响应的未知字段不再致命**:`EmbeddingResponse` 挂上 `@JsonIgnoreProperties(ignoreUnknown = true)`。
  llama.cpp 的 `/v1/embeddings` 每个 item 都会回一个 `index`,而 DTO 里只有 `embedding`;
  一旦解析器恰好是严格模式(比如测试里裸 `new ObjectMapper()`),每次 embed 都会抛
  `UnrecognizedPropertyException` —— 而调用方是“吞掉异常并降级”,于是检索会**静默**退化成纯关键词,
  只表现为“换个说法就搜不到”。把宽容写在 DTO 上,不再依赖调用方用什么 mapper
  (顺带加了 `LocalEmbeddingClientTest`,把这个坑锁死)。
- **`/api/health` 现在能看出“语义检索是不是已经废了”**:新增 `degraded` 与 `embedding.{status,model}` ——
  `LocalEmbeddingClient` 走轻量 `GET /health` 探测(600ms 超时,**不占 embedding 算力**,可以随便被监控轮询)。
  服务活着但 embedding 挂了 = 检索悄悄变笨,以前没人知道;现在监控直接盯 `degraded` 就行。
- **长期记忆召回:embedding 不可用时不再“只认时间”**(C 段补强):
  - 退化路径改成**字面命中优先、同分再比时间** —— 提问里 2-gram 命中越多的记忆排越前;
    一条都没命中时分数全部并列 0,**自动退回原来的按时间顺序**(行为只增不改)。
    治的是“早些时候说过的重要事情被后来记的挤出前 K 名、从此召不回来”。
  - 注入的记忆说明语补一句:**“这里可能不全,没有的信息照实说‘不记得’,不要编造用户说过的话”**。
- **提示词保真度**(C 段补强):`prompts/session-summarize.txt` 要求“用户明确陈述过的事实性信息必须原样保留,
  宁可摘要长一点”(上限 200 → 400 字);`prompts/memory-extract.txt` 补两条硬规矩
  ——“照用户原话记,别替他总结掉细节”“用户明确要求记住的,keep 给 1.0”。

### 变更 / Changed
- **视觉模型不再写死 Kimi(“去厂商化”)** —— 以前“哪家模型”是散在代码里的:`MoonshotVisionClient` 这个类名、
  `System.getenv("MOONSHOT_API_KEY")` 这句写死的密钥变量名、`VisionRequest` 里硬编码的 K2.6 专属字段
  `thinking:{type:"disabled"}`、OCR 引擎标识 `kimi`、以及兜底引擎“硬认 kimi 这个名字”。现在:
  - **一个通用实现** `OpenAiCompatibleVisionClient`(走 OpenAI 兼容协议):端点 / 模型 / 密钥 / 厂商专属字段
    全部来自 `fa.vision.*`;换厂商 = 改 yml,代码不动(阿里百炼 / 智谱 / 火山方舟 / 硅基流动 / 本地 Ollama 都能接)
  - 密钥解析统一到 `ApiKeys`(`api-key` 直接写值优先,否则读 `api-key-env` 指定的环境变量),DeepSeek 也改用它 ——
    “密钥从哪来”只有一套规则,而且**在发请求之前**就校验(不会再让人传完图才看到 401)
  - 厂商专属字段走 `fa.vision.extra-body-json`(JSON 文本,原样合并进请求体),**启动时解析,写错启动即报错**。
    用字符串而不是嵌套结构,是为了 profile 覆盖时能**整段替换**(嵌套 Map 是逐键合并,旧键删不掉)
  - 请求体改成显式拼 `LinkedHashMap`(record 加不了动态字段);消息结构仍是强类型 `VisionMessage`
  - **OCR 引擎标识 `kimi` → `vision`**(`KimiOcrEngine` → `VisionOcrEngine`):老配置/老命令里的 `kimi`、`moonshot`
    仍作**别名**认到 `vision`(`FA_OCR_ENGINE=kimi` 不用改);兜底引擎改成配置项 `fa.ocr.fallback-engine`
    (不再硬认一个名字 —— 换了厂商不会静默失去兜底);`fa.ocr.kimi.page-gap-ms` → `fa.ocr.vision.page-gap-ms`
    (旧键名仍认)、`fallback-to-kimi` → `fallback-to-vision`(旧键名仍认)
  - 加了两套 **profile 预设**:`qwen`(阿里百炼)/ `ollama`(本地离线免费)—— 一份 jar 装多家模型
- **网页界面:滚轮不再满屏乱滚,底下那块空白也没了("应用外壳")**。
  以前是**两套滚动** —— `body` 能整页滚,`.messages` 里还自带一条滚动条;
  再碰上 `main { align-items: start }` + 右栏「知识库」很矮,聊天框跟着塔矮,底下就空一大块。
  现在整页做成**固定视口高度**:`html,body{height:100%}` + `body{overflow:hidden}`,
  `header` 固定高、`main` 吃掉剩下的高度,三栏 `align-items: stretch` 都撑满 ——
  滚轮落在聊天框就滚聊天记录、落在左栏就滚对话列表、落在右栏才滚知识库,**页面本身永远不动**。
  窄屏(`<=860px`)三栏要堆成一列、100vh 装不下,那里仍保留整页滚动
  (所以窄屏媒体查询故意放在文件末尾 —— 同为 `.messages`/`.chat` 的选择器,后面的才盖得住前面的)。
- **重命名 / 删除对话改成页面内对话框**(新 `uiDialog`)白底卡片 + 主题蓝按钮,和「🧠 记忆」抽屉同一套模子。
  以前是 `window.prompt` / `window.confirm` —— 灰色系统弹窗,样式完全不能控。
  回车 = 确定、`Esc` / 点灰底 / 「关闭」= 取消;删除用**红色**「删除」按钮区分破坏性操作。
  重命名**留空 = 不改**、名字没变就不白跑一次请求。
- 标题栏宽度和主区域对齐(`1100px` → `1280px`),不再差一截。

### 新增 / Added
- **`/api/health` 多报一段视觉链路**:`vision.{status, provider, model}` —— 一眼看出“现在是哪家、哪个模型、配没配好”。
  它只做“配置可用”判定(**不真调接口**,省配额),且**不参与 `degraded`**(视觉只影响图片链路,文字问答不受影响)
- 视觉客户端测试 `OpenAiCompatibleVisionClientTest`(9 例,本地 `com.sun.net.httpserver` 打桩):请求体结构 /
  data URL 前缀 / 空字段不进 JSON / `extra-body` 合并与清空 / 缺密钥时不发请求 / 429·5xx·空内容可重试 / 4xx 不重试 /
  `extra-body-json` 写错启动即报错;`OcrEnginesTest` 补“别名”与“兜底引擎可配”用例
- **共享记忆域(让两个对话记的是同一个人)**:以前每个对话的记忆是隔离的,
  “物理”里说过的偏好,到“物理作业”里又要重说一遍 —— 现在可以把几个对话的**记忆域指到同一个**
  (`PUT /api/conversations/{id}/memory-scope`,前端在记忆抽屉里给了一个「🔗 记忆域」下拉)。
  - **共享 = 指向同一份存档,不做复制同步** —— 一边加一条、删一条,另一边立刻也看得到;
    不会出现“同步漏了一次,两边不一样了”这种中间态
  - **存的是“根”**:把丙共享到已共享的乙,最终指向的是甲(A → B、B → C 这种长链不会越接越长,也不会成环;
    就算库里已经存在长链,解析时也会**顺着指针走到根**,不会出现“以为共享了、其实一个读写 B、另一个读写 C”的错位)
  - **拆开只改指向**:共享期间那一份留在原处,不会跟着搬走;拆开后的对话得到一份干净的自己的记忆
  - **共享对象被删了**仍能解析:停在那个名字上 —— 记忆文件还在(数据不丢),也不会串到别人家
  - 拆开/加入都经过 id 白名单正则与“目标必须存在”校验(路径穿越依旧被挡在门外)
  - 新增测试:`ConversationServiceTest` +8(默认/共享/存根/链式解析/幂等/拆开/目标被删/非法目标)、
    `LongTermMemoryTest` +5(共享后两边看得到、删一条两边都少、拆开不搬家、后台提炼写进共享槽、条目记着出身会话)、
    `ConversationControllerTest` +2(透传 scopeId、body 缺失不 NPE)
- **公式与 Markdown 渲染(前端)**:模型本来就是按 Markdown + LaTeX 输出的,浏览器不认,直接显示就满屏 `**` 和 `$$`。
  本地引入 **KaTeX 0.16.11**(含字体)+ **marked 15.0.7**(共 ~600KB,打进 jar,**离线可用**,不走 CDN):
  - **流水线:抠公式 → 转义 → Markdown → 塞回公式**。顺序不能换 ——
    公式里 `(\omega_2-\omega_1)` 的下划线会被 Markdown 当成*斜体*、`\varphi` 的反斜杠会被当转义符,
    必须先抠出来换成占位符,等 Markdown 跑完再交给 KaTeX
  - **只转义 `<` 和 `&`**(不是全套 HTML 转义):挡住 `<` 就能把标签堵死(`<script>` / `<img onerror>`),
    而 `>` 得留着 —— 否则 `> 引用` 就不是引用块了;因此不需要额外的 XSS 消毒库
  - **不误伤**:单 `$` 要满足“后面不跟空白、收尾前不是空白、收尾后不跟数字”,所以价格 `$5` 不会被当公式;
    代码块/行内代码先掩起来再抠公式,所以 `` `$PATH` `` 和示例里的 `$$...$$` 保持原样
  - **不吐鬼画符**:KaTeX 解析不了就**回退显示原文**(总比一片红或空白强);
    流式生成时仍是纯文本逐字追加,到 `done` 才整体重画 —— 天然避免“半截公式”
  - 长公式横向可滚,不会把气泡撑爆;样式只补在 `.answer.rich` 里,不影响流式阶段的排版
- **流式输出(SSE)**:新增 `POST /api/agent/stream`,答案**边生成边显示**,还能中途「停止」——
  前端不再是那个傻等计时的进度条,而是真的在“看着它写”。
  - **不动老接口**:`POST /api/agent` 一字未改(命令行 / 脚本 / 老测试继续用它),新旧并存
  - **不加 WebFlux**:仍是 Spring MVC + `SseEmitter` —— 不为了一个接口把整套编程模型换掉;
    阻塞的模型调用扔到自己的线程池,不占 Tomcat 请求线程
  - **事件协议**:`meta`(开场,带 conversationId)/ `status`(“正在查资料(search_knowledge)…”) /
    `reset`(刚才吐的那半句不算)/ `delta`(新正文)/ `done`(最终答案 + trace,前端用它重画) / `error`;
    另外每 15 秒一条 `: ping` 心跳 —— 工具检索/图片识别可能几十秒没输出,不心跳会被中间代理当成死连接掐掉
  - **为什么需要 `reset`**:模型在“决定调工具”的那一轮常常先自言自语半句(“我来查一下教材…”),
    那不是答案。前端收到 `reset` 就把这半句擦掉,等真正的答案重新逐字出现(冒烟里实测到了这一轮)
  - **重试纪律**:只在“**还一个字都没吐出去**”时重试;已经吐过字再断,只能发 `error` 收场 ——
    靠异常类型区分(还没吐字 = `TransientApiException` 可重试,吐过字 = `AgentException` 不重试)
  - **落库、记忆提炼一步不少**:`done` 之前该写的写、该在后台提炼的照提,流式不影响任何既有链路
  - **循环只有一份**:`AgentService` 把 ReAct 主循环抽给“一次性 / 逐字回调”两种取回复方式共用 ——
    各写一遍的话,以后改步数上限、加工具必然漏改其中一份
  - 前端:`fetch` + `body.getReader()` 手工解帧(提问是 POST,用不了 `EventSource`)、逐字渲染、
    「停止生成」走 `AbortController`;浏览器太老没有 `ReadableStream` 时自动退回一次性接口
  - 新增测试:`AgentStreamControllerTest`(5:事件顺序 / 工具轮 reset+status / 出错也要收尾 / 空问题与坏会话在建流前就拒绝)、
    `DeepSeekClientStreamTest`(7:逐字解析 / 工具碎片拼接 / 心跳忽略 / 两种重试纪律)
- **检索评测集(golden set)**:`src/test/resources/retrieval_eval.json`(**40 条** = 35 条有答案 + 5 条故意无答案)
  + `knowledge/RetrievalEvalTest` —— 四个指标 **recall@5 / MRR / 页命中@5 / 拒答率**,真库**拷到 @TempDir** 再跑。
  首次实测:recall@5 **0.86** / MRR **0.77** / 页命中@5 **0.89** / 拒答率 **1.00**,门槛按实测收在 0.80/0.70/0.80/0.80;
  embedding 服务没开自动跳过,CI 不会因为“没起服务”变红。题目是看着真库手写的 —— 量的是“检索稳不稳 + 会不会乱答”,不是绝对客观精度。
- **长期记忆(跨轮累积的事实 / 偏好)**:短期记忆管“这个会话刚才聊到哪儿”,长期记忆管“关于这个人已经确定什么” ——
  它跨轮、跨窗口,按记忆域存在磁盘上:
  - **提炼时机**:攒够 `fa.memory.long-term.extract-every-turns`(默认 6)轮就在**后台线程**里提炼一次
    —— 它是顺手记笔记,不该让用户多等一秒(摘要必须同步,因为这一轮就得用)
  - **两道闸滤闲聊**:①规则预筛(寒暄 / 确认 / 太短 / 半轮,连模型都不调)②模型打分 `keep >= 0.5`
    (`prompts/memory-extract.txt` 外置,明写“不记知识库状态、不记助手自己这轮怎么答的”)
  - **水位 `Conversation.memoryCount`**:只进不退,推的是“提炼那一刻快照里的消息数” ——
    后台提炼与新消息并发时,不会把没提炼的那几轮跳过去
  - **去重靠向量**:新条目与已有条目余弦 > `dedup-similarity`(默认 0.9)就合并(更新原文、不新增)
  - **召回**:每轮提问拿问题去记忆域里取最相关的 `recall-top-k`(默认 5)条,作为 **system 消息**
    插在系统提示词之后、历史之前;故意不设相似度阈值(同一记忆域天然同主题,条数也少)
  - **全链路降级**:embedding 没开 → 提炼照样入库(只是没向量)、召回退化成“按时间取最近几条”、
    去重自动关闭;提炼失败 → 水位照推(不然后面每轮都对着同一段重试);**任何一步都不影响问答**
  - 落盘 `data/memory/<记忆域>.json`(一个记忆域一个文件,带 embedding 模型指纹;模型换了旧向量作废),
    路径穿越同样被白名单正则挡住;`scopeId` 默认 = 会话 id,**下批次(共享记忆域)就是把几个会话指到同一个 scopeId**
  - 顺带把知识库里的私有余弦函数提成 `llm/Vectors`(两处共用,不再各写一份)
- **记忆治理(看得见 / 改得动 / 删得掉)**:
  - **钉住(`pin`)** —— 用户自己加的、以及**用户明确让记住的**(提炼提示词让它给 `keep=1.0`)
    入库即钉住;记忆域满了要淘汰时**一条都不动**。提炼器现在把分数一并带出来
    (`MemoryCandidate`),不再只交一串字符串
  - **总量上限 + 淘汰** —— 新增 `fa.memory.long-term.max-stored`(默认 500):超了就淘汰
    “**最久没被召回**”的那条(排在“钉住的”之前的先走)。新增 `MemoryEntry.lastAccessAt`
    与 `MemoryStore.touch()` —— 召回时顺手记一笔,但**5 分钟内不重复写盘**(召回在问答热路径上)
  - **REST + 前端抽屉** —— `GET/POST/PATCH/DELETE /api/memory`、`DELETE /api/memory`(忘掉全部)、
    `GET /api/memory/export`(带 `Content-Disposition` 下载);左栏「🧠 记忆」能列 / 加 / 改 / 钉 / 删 / 导出,
    按钮上还带着“记住了几条”
  - **不把向量吐出去** —— 出口统一走 `MemoryView`(embedding 是 512 个 float,前端一个都用不上)
  - **旧文件兼容** —— 老的 `data/memory/*.json` 没有新字段也能读:`pinned=false`、`origin=auto`、
    `lastAccessAt` 兜成创建时间
  - 新增测试:**`LongTermMemoryTest`(10)**、`MemoryStoreTest`(9)、`LlmMemoryExtractorTest`(8)、
    `MemoryFilterTest`(7)、`VectorsTest`(4);`AssistantServiceTest` 补长期记忆注入位置 / 提炼时机 / 提炼失败不影响问答
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
- 单元测试 **225 → 268**(新增:长期记忆提炼时机 / 水位只进不退 / 相似合并 / embedding 降级入库与召回 / 召回排序 / 召回消息格式 / 提炼解析容错(围栏 / 套壳 / 字符串壳 / 行模式) / 规则预筛 / 记忆落盘与模型指纹)
- 单元测试 **268 → 285**(新增:分数带出与“用户要求记住”识别 / 钉住 / 上限淘汰顺序 / 手动加改删清空 / 召回touch / 旧文件缺字段兼容 / 记忆接口翻译与“向量不外泄”)

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
