# Formal Agent

> 我的第一个开源 AI Agent 项目 —— 边学边建,公开记录。
> My first open-source AI Agent project — building it in public while learning.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
![Status](https://img.shields.io/badge/status-early%20dev-orange)
![Java](https://img.shields.io/badge/Java-17+-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)

---

## 项目简介 / About

**中文**:这是我学习 Java 后端 + AI Agent 开发过程中做的第一个开源项目。
它要把「教材」变成「可问答的知识库」:

1. **入库**:把教材(PDF / 图片 / 文本)读进来 —— 扫描版 PDF 走 **OCR**(本地 RapidOCR 或视觉模型),
   原生 PDF 直接抽文字层 —— 切块后交给大模型**提取知识点**(带页码出处),落到知识库;
2. **问答**:给一张图片(或提问),先识别内容,再**从知识库里按语义检索**相关知识点,
   最后让模型**只依据这些知识点**用大白话回答,并**标明出处**;
3. 同时支持与模型的普通多轮对话。

**English**: My first open-source AI Agent project. It ingests textbooks (text-layer PDFs via
`PDFTextStripper`, scanned PDFs / images via a vision model OCR), extracts knowledge points
(with page-level provenance) into a knowledge base, then answers questions **grounded on
semantic (embedding) retrieval** over that knowledge base — with citations.

> **当前状态 / Status**: `0.1.0-SNAPSHOT`。**核心闭环已跑通**(入库 + 向量检索 + 带出处问答),
> 并已完成 **Spring Boot 工程化**(配置类型安全 / profile / 可执行 jar)。
> Core loop works (ingest → vector retrieval → grounded QA with citations); Spring Boot
> engineering (typed config / profiles / executable jar) is done.

---

## 功能特性 / Features

- **文档入库(三种来源)**:`.txt` / `.md` 文本、原生 PDF(文字层)、**扫描版 PDF / 图片(OCR)**;产出带页码出处的知识点
  - OCR 引擎可换:本地 **RapidOCR**(PP-OCRv4,纯 Java/CPU/离线,一页几秒)或**视觉模型**(能读公式,慢且有限流);
    本地读糊的页可**自动回退**视觉模型重读(见下方「OCR 引擎」)
  - **视觉模型不写死厂商**:走 OpenAI 兼容协议,端点 / 模型 / 密钥变量 / 厂商专属字段全在配置里(`fa.vision.*`),
    换模型只改 yml(或用现成的 `qwen` / `ollama` profile),**代码一行不动** ——
    阿里百炼 / 智谱 GLM-4V / 火山方舟豆包 / 硅基流动 / 本地 Ollama 都能接
- **语义检索(真 RAG 的第一步)**:知识点 → **embedding 向量**,检索按**余弦相似度**取 top-K;
  embedding 服务不可用时**自动降级**为关键词匹配(不会因此搜不了)
- **带出处的问答**:图片 → 识图 → 检索 → 「只依据知识点」生成回答 + 【出处】
- **公式与 Markdown 渲染**:模型是按 Markdown + LaTeX 输出的,前端本地集成了 **KaTeX + marked**(带字体,离线可用)
  —— `$$A=\sqrt{A_1^2+A_2^2+2A_1A_2\cos(\varphi_1-\varphi_2)}$$` 这种公式、`**粗体**`、列表、表格、代码块都是真排版,不再是一屏符号;
  流水线是“**先抠公式 → 转义 → Markdown → 再塞回公式**”(顺序不能换:公式里的 `\omega_2` 下划线会被 Markdown 当成斜体);
  价格 `$5`、代码块里的 `$$` 不会被误当公式;
  **`\sqrt` 认不了、渲染炸了、库没加载上** → 一律退化成纯文本显示原文(绝不吐一片空白或红字,更不会把回答弄丢)
  - **流式期间也会边生成边排版**:每 ~150ms 重画一次,但**只重画“已经闭合”的内容** ——
    半截的代码块 / 半截的块级公式先不碰(否则整段排版会被 Markdown 弄乱),字照常先按纯文本长出来;
    中途点「停止」或连接断了,也会把已有的半截内容**排版好再停**,而不是留下一屏 `**` 和 `$$`
  - ⚠ 静态资源已设 `Cache-Control: no-cache`(每次用 Last-Modified 校验一下)→ 改了 JS/CSS **普通刷新就生效**,不用记着按 Ctrl+F5
- **流式输出(SSE)**:`POST /api/agent/stream` —— 答案**边生成边显示**,还能中途「停止」;
  事件 `meta / status / reset / delta / done / error` + 每 15 秒一条 `: ping` 心跳;
  模型在“决定调工具”那一轮里的自言自语会被 `reset` 作废;
  **只在“还没吐字”时才重试**(否则用户会看到两遍半截回答);老接口 `POST /api/agent` 一字未改,新旧并存
- **Agent 化(工具调用 / ReAct)**:把"检索"交给模型当**工具**,由它自己决定**查不查、查几次、查什么**;
  响应里带 `trace`(它到底调了哪些工具) —— 从"固定流水线"变成"会自己拿主意的 Agent"
- **工具集**:`search_knowledge` / `knowledge_overview` / `get_knowledge_page` / **`calculator`**(自写安全表达式求值器)/ **`unit_convert`**
- **混合检索 + 阈值**:向量 + 关键词加权;相似度不够就当作“没找到”(`fa.knowledge.min-score`)
- **多图问答**:`POST /api/agent`(multipart)一次可传 **最多 10 张**图 → 逐张视觉识别(按序号合并成一段文字)→ Agent 回答;单张失败自动跳过
- **接口鉴权**:管理接口(`/api/ingest`、`/api/knowledge/source`)需请求头 `X-Admin-Key`
- **知识库来源管理(删 / 换)**:可按“哪本书”(来源)整本**删除**或**重灌** —— 
  `IngestMain --list / --remove-source / --replace` 与 `DELETE /api/knowledge/source` / `POST /api/ingest?replace=true`;
  灌库漏页(`failedPages`)会记进断点并在下次开跑时自动补灌,详见下方「灌库断点」
  来源名可以只写一部分,但**只有唯一命中才动手**(歧义/没命中就拒绝并列出候选,避免删错书)
- **灌库断点(每 40 页落一次盘)+ 漏页兜底**:大教材(扫描件)要 OCR + 提炼一两个小时,中途断了不必从头再来 ——
  每 `fa.extract.block-size` 页(默认 40)落一次盘,**重跑同一条命令即从上一块续**;`IngestMain --progress` 看灌到哪、`--restart` 推倒重来。
  OCR 单页失败(限流/图糊)会**记下页号**,下次跑先补灌这几页 —— 不会静默丢页
- **Prompt 模板外置**:文案在 `resources/prompts/*.txt`
- **网页前端**:内置单页问答界面(`http://localhost:8080/`)—— 文字提问 / 一次最多选 10 张图(缩略图可逐张删除,上传前自动压图)/ 看知识库;
  界面是**固定视口高度的“应用外壳”**:页面本身不滚,滚轮落在哪儿就滚哪儿(聊天记录 / 对话列表 / 知识库),
  不会“一滚滚走整页”、也不留大片空白(只有窄屏 `<=860px` 堆成一列装不下时,才改回整页滚);
  重命名 / 删除对话走页面内的白底对话框(不是浏览器原生 `prompt`/`confirm`);
  教材里没有的内容 → 「点击这里联系开发者」跳转 `/contact.html` 扫码加微信(**入库能力不在用户端暴露**)
- **多会话(对话框)**:默认对话 + 新建 / 改名 / 删除 / 切换;**记忆默认互相隔离**,会话以 JSON 落盘(`data/conversations/`)
- **共享记忆域**:两个对话想让助手“记住的是同一个人”,就把它们的**记忆域指到同一个**(记忆抽屉里的「🔗 记忆域」下拉选择要共用的对话)
  —— 是**同一份存档**,不是复制同步:一边加一条、删一条,另一边立刻也看得到,不存在“同步漏了”的中间态;
  拆开时只改指向,共享期间那份留在原处(不会把别人的记忆搬走);链式指向会**自动走到根**,不会“以为共享了、其实各读写各的”
- **多轮上下文(短期记忆)**:模型每次都能看到“这个会话之前聊过什么” —— 自动带**最近几轮原文**,
  超出窗口的旧对话压成**滚动摘要**(`fa.memory.short-term.*`);摘要失败有本地兜底,历史不白丢
- **长期记忆**:跨轮、跨窗口地记着“关于用户已知什么” —— 攒够几轮(默认 6)在**后台**提炼
  (规则预筛 + 模型打分,只留值得记的),提问时按相似度**召回 top-K** 带进上下文;
  按**记忆域**(`scopeId`)分桶落盘(`data/memory/`),embedding 没开就退化成**字面命中优先 + 按时间取最近几条**(`fa.memory.long-term.*`)
- **记忆治理**:**看得见、改得动、删得掉** —— 前端左栏「🧠 记忆」是一个抽屉:列出这个对话记住的事,
  能手动加一条(直接钉住)、改文字、钉住/取消、删一条、一键忘掉全部、导出 JSON
  (`GET /api/memory`、`POST /api/memory`、`PATCH|DELETE /api/memory/{id}`、`DELETE /api/memory`、`GET /api/memory/export`);
  **钉住**的(用户自己加的、或明确让记住的)永不被自动淘汰;一个记忆域超过 `max-stored`(默认 500)就淘汰"最久没被召回"的那条
- **会话存档**:JSON(`data/conversations/`,一个会话一个文件)+ 老的 Java 原生序列化(`.bin`,CLI 用)
- **结构化输出**:调用时开启 JSON mode(`response_format=json_object`)
- **健壮性**:超时 + **重试 + 退避**(Spring Retry / AOP);音频类长任务失败可跳过
- **可观测**:`GET /api/health` 除了“活着没 / 知识库多少条”,还给一个 **`degraded` 标志** ——
  embedding 挂掉时检索会**静默**退化成纯关键词(只表现为“换个说法就搜不到”),
  不报出来的话没人会发现变差(探测走轻量 `GET /health`,不占 embedding 算力)
- **可评测**:`RetrievalEvalTest` + `retrieval_eval.json` 是检索的“尺子” ——
  四个指标 recall@5 / MRR / 页命中@5 / 拒答率,`mvnw -o test -Dtest=RetrievalEvalTest` 一跑就知道检索有没有变差
- **配置**:`application.yml` + `@ConfigurationProperties`(类型安全)+ **profile**
- **调用日志**:按天滚动到 `logs/api-YYYY-MM-DD.log`(经 SLF4J/Logback)
- **HTTP 接口(REST)**:`POST /api/agent`、`POST /api/ingest`、`DELETE /api/knowledge/source`、`GET /api/knowledge`、`/api/conversations`、`/api/memory`(记忆的看 / 加 / 改 / 钉 / 删 / 导出)、`GET /api/health`
- **Spring Boot 工程化**:可执行 fat jar(`java -jar`)、内嵌 Tomcat、内嵌日志(SLF4J/Logback)
- **单元测试**:**285 个**,含 Boot 容器冒烟测试与配置绑定测试(不联网、秒级)

---

## 技术栈 / Tech Stack

| 项目 | 版本 | 说明 |
|---|---|---|
| Java | 17+ | 编译 `release=17`,JDK 17 及以上均可 |
| Maven | 3.9+ | 构建与依赖管理(含 Maven Wrapper) |
| **Spring Boot** | **3.5.16** | 自动配置 / 可执行 jar / 内嵌日志(底层 Spring 6) |
| Spring Boot Web | (Boot BOM 管) | **内嵌 Tomcat + Spring MVC**(REST 接口) |
| Spring Retry | (Boot BOM 管) | 声明式重试(`@Retryable` + AOP) |
| Jackson | (Boot BOM 管) | JSON 序列化 / 反序列化 |
| Apache PDFBox | 3.0.8 | PDF 文字层提取 + 逐页渲染(供 OCR) |
| RapidOCR(onnxruntime) | 0.0.7 | **本地 OCR**(PP-OCRv4,CPU/离线,自带 native 库与模型) |
| JUnit | (Boot BOM 管) | 单元测试 |
| **DeepSeek** | `deepseek-chat` | 文本对话 / 知识点提炼 |
| **视觉模型**(OpenAI 兼容) | `kimi-k2.6`(可换) | 扫描件 OCR(可当本地 OCR 的兜底)/ 图片理解;端点 / 模型 / 厂商字段全由 `fa.vision.*` 配置决定 |
| **本地 embedding** | `bge-small-zh-v1.5` | 经 llama.cpp `llama-server` 提供 OpenAI 兼容接口 |

---

## 快速开始 / Quick Start

### 前置要求(Prerequisites)

- **JDK 17+**
- **Maven 3.9+**(或用自带的 `mvnw` / `mvnw.cmd`)
- 环境变量:
  - **`DEEPSEEK_API_KEY`** —— 文本对话 / 提炼([platform.deepseek.com](https://platform.deepseek.com))
  - **`MOONSHOT_API_KEY`** —— 视觉 OCR / 图片问答([platform.moonshot.cn](https://platform.moonshot.cn))
    (变量名也能配:换厂商就把 `fa.vision.api-key-env` 指到对应变量,如 `DASHSCOPE_API_KEY`;
    也可用 `fa.vision.api-key` 直接写值)

```powershell
# Windows PowerShell(设置后需重开终端 / IDEA 才生效)
setx DEEPSEEK_API_KEY "你的key"
setx MOONSHOT_API_KEY "你的key"
```

### (可选但推荐)启动本地 embedding 服务

语义检索依赖一个本地 embedding 服务。**不启动也能跑** —— 只是检索会降级为关键词匹配。

```powershell
# 需要:llama.cpp 的 llama-server.exe + bge-small-zh 模型(路径见脚本内注释)
start-embedding.bat          # 起在 http://127.0.0.1:8090
```

### 构建与运行

```bash
mvn clean test               # 跑测试(285 个)
mvn clean package            # 打包 → target/formal-agent-0.1.0-SNAPSHOT.jar

# 【默认】启动 HTTP 服务(内嵌 Tomcat,端口 8080)
java -jar target/formal-agent-0.1.0-SNAPSHOT.jar

# 交互式命令行(想聊天/传文件时)
java -jar target/formal-agent-0.1.0-SNAPSHOT.jar --fa.cli.enabled=true

# 带 profile(dev 打开调试输出)
java -jar target/formal-agent-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

也可以在 IDEA 里直接运行 `FormalAgentApplication`(绿色 ▶)。

启动后浏览器打开 <http://localhost:8080/> —— 就是网页问答界面。

### 命令行怎么用(需 `--fa.cli.enabled=true`)

启动后是交互式提示符:

- **直接说话** = 普通多轮对话
- **粘 `.md` / `.txt` / `.pdf`** = 提取知识点(写一份 `<文件名>.knowledge.md`)
- **粘图片(`.png` / `.jpg`)** = 识别图片 → **对照知识库回答(带出处)**
- 输入 `exit` 退出(自动存档会话)

> **批量入库 / 清单 / 删除**都用 `cli.IngestMain`(带 Boot 的命令行,不起 Tomcat):
>
> ```
> IngestMain --list                        列出知识库各来源及条数
> IngestMain --progress                    查看灌库断点(灌到第几页、还有哪几页没灌成功)
> IngestMain <教材文件>...                 导入(默认带断点:每 40 页落一次盘,断了重跑即续)
> IngestMain --replace <教材文件>          重灌:先删该来源旧条目,再导入
> IngestMain --restart <教材文件>          忽略旧断点,从第 1 页重来
> IngestMain --remove-source <来源名>      按来源删除(可只写文件名的一部分)
> ```
>
> 📌 **灌大书(扫描件)必备**:一本 400 页的扫描件要 OCR + 提炼一两个小时,没有断点中途一挂就得从头再来。
> 现在每 `fa.extract.block-size` 页(默认 **40**)落一次盘:断了就**重跑同一条命令**,已灌过的页不会重来;
> `--progress` 随时看灌到哪了。断点粒度可用环境变量 `FA_INGEST_BLOCK_SIZE` 调,进度文件在 `FA_CHECKPOINT_DIR`(默认 `data/checkpoints`)。
>
> 🔁 **漏页兜底**:扫描件走 OCR 时单页可能失败(比如接口限流)—— 这套机制**不会把失败页静默丢掉**:
> 失败的页号会记进断点文件,下次跑同一条命令时**先补灌这几页**(补一页落一次盘),补上即划掉;
> 一直补不上的页也不阻塞,只在 `--progress` 里列出来。所以 OCR 打嗝不用整本重来。
>
> 🔤 **OCR 引擎**(`fa.ocr.engine`,可用环境变量 `FA_OCR_ENGINE` 覆盖):
> - `vision`(默认;**旧名 `kimi` 仍作别名认它**)—— 远程视觉模型,慢(约 10~17 秒/页)但**能把公式读成 LaTeX**;
>   受服务商分钟级限流时,把 `fa.ocr.vision.page-gap-ms`(环境变量 `FA_OCR_PAGE_GAP_MS`,旧键名 `fa.ocr.kimi.page-gap-ms` 也认)调大 ——
>   它的语义是「两次调用的开始时刻至少隔这么久」,想稳在 3 次/分钟以内就设 `21000`
> - `rapid` —— 本地 PP-OCRv4,**约 3 秒/页、离线、零成本**;中文正文质量跟视觉模型相当,
>   但**公式/化学式会读乱**(公式密集的理科书建议仍用视觉模型,或配合回退使用)
> - 两者可组合:`fa.ocr.fallback-to-vision`(默认 `true`;旧键名 `fa.ocr.fallback-to-kimi` 也认)
>   在 `rapid` 读得可疑时自动换**配置的兜底引擎**(`fa.ocr.fallback-engine`,默认 `vision`)重读那一页 ——
>   兜底不再“硬认某个名字”,换厂商不会静默失去兜底
> - **换视觉厂商不改代码**:端点 / 模型 / 密钥变量 / 厂商专属字段都在 `fa.vision.*`
>   (`api-url` / `model` / `provider` / `api-key-env` / `extra-body-json`);也可以直接用 profile:
>   `--spring.profiles.active=qwen`(阿里百炼)或 `ollama`(本地,离线免费)
> - 阈值:`fa.ocr.quality.min-chars`(默认 30)、`fa.ocr.quality.min-chinese-ratio`(默认 0.2)
> - **去噪**:`fa.ocr.watermark-lines`(默认 `夸克扫描王,极速扫描,就是高效`)—— OCR 结果里有行含这些关键字就整行丢掉,
>   免得扫描 App 的水印变成知识点;留空 = 不过滤
> - **空白页不算失败**:读出来是空的(封面/白纸/篇扉页)会拿页图复核,墨迹 < 2% 就当「本来就没字」;
>   真有东西没读出来才记漏页、下次补灌
>
>
> ⚠️ 应用正在跑的时候,请改用 REST(`POST /api/ingest?replace=true` / `DELETE /api/knowledge/source?name=...`):
> 运行中的进程把知识库全放在内存里,直接改磁盘文件会被它下一次写盘盖回去。
> 若终端里中文显示成 `?`,给 java 加 `-Dstdout.encoding=UTF-8` 或到 IDEA 里跑。

---

## HTTP 接口 / REST API

默认启动即为 HTTP 服务(`http://localhost:8080`)。

| 方法 & 路径 | 作用 | 请求体示例 |
|---|---|---|
| `POST /api/agent` | **Agent 问答**(模型自己决定调哪些工具) | `{"question":"位移是多少?","conversationId":"default"}` → 返回 `answer` + `steps` + `trace` + `conversationId`(`conversationId` 可省) |
| `POST /api/agent`(multipart) | **多图问答**:最多 10 张,逐张识别 → Agent 回答 | `files=@图1.png` `files=@图2.png`(+ 可选 `question` / `conversationId`) |
| `POST /api/agent/stream` | **流式问答(SSE)**:边生成边推送,可中途断开 | 请求体同 `POST /api/agent`;返回 `text/event-stream`,事件 `meta/status/reset/delta/done/error` |
| `GET /api/conversations` | **会话(对话框)列表** + 默认会话 id | — |
| `POST /api/conversations` | 新建会话 | `{"title":"物理复习"}`(可省) |
| `GET /api/conversations/{id}` | 读某个会话的完整聊天记录 | — |
| `PATCH /api/conversations/{id}` | 给会话改名 | `{"title":"新名字"}` |
| `PUT /api/conversations/{id}/memory-scope` | **共享记忆域**:指向另一个会话(共用同一份长期记忆) / `null` 拆回独立 | `{"scopeId":"c1234567890ab"}` 或 `{"scopeId":null}` |
| `DELETE /api/conversations/{id}` | 删除会话(默认对话不许删) | — |
| `GET /api/memory` | **这个对话记住了些什么**(新的在前) | `?conversationId=xxx`(可省 = 默认对话) |
| `POST /api/memory` | 手动记住一件事(**直接钉住,永不淘汰**) | `{"conversationId":"default","text":"我叫鹏哥"}` |
| `PATCH /api/memory/{id}` | 改文字 / 钉住 / 取消钉住(不传的字段 = 不改) | `{"text":"...","pinned":true}` |
| `DELETE /api/memory/{id}` | 删一条 | `?conversationId=xxx` |
| `DELETE /api/memory` | **忘掉这个对话的全部记忆** | `?conversationId=xxx` |
| `GET /api/memory/export` | 导出记忆 JSON(带 `Content-Disposition`,浏览器直接下载) | `?conversationId=xxx` |
| `POST /api/ingest` | 教材入库(管理端,需 `X-Admin-Key`)—— **上传文件**,不收“路径” | `multipart/form-data`,`file=@物理.pdf`;加 `?replace=true` 则**先删同来源旧条目再入库** |
| `DELETE /api/knowledge/source` | 按来源删除(管理端,需 `X-Admin-Key`);`name` 可只写一部分,**唯一命中才删** | `?name=物理` |
| `GET /api/knowledge` | 知识库概况(总数 / 来源清单 / **各来源条数** / 抽样) | `?sample=5` |
| `GET /api/health` | 健康检查:**含 embedding 健康度**(`degraded` + `embedding.{status,model}`) | — |

```powershell
# 问一句(Agent 自己决定要不要查、查几次)
curl -X POST http://localhost:8080/api/agent -H "Content-Type: application/json" ^
     -d "{\"question\":\"沿闭合曲线跑一圈回到原点,位移是多少?\"}"

# 新建一个对话,再往它里问(不带 conversationId 就落到"默认对话")
curl -X POST http://localhost:8080/api/conversations -H "Content-Type: application/json" -d "{}"
curl http://localhost:8080/api/conversations

# 看知识库
curl http://localhost:8080/api/knowledge?sample=3

# 入库(上传文件;接口**不接受“路径”参数**,从根本上堵死任意文件读取)
curl -F "file=@C:/资料/物理.pdf" http://localhost:8080/api/ingest
```

> 端口的改法:`--server.port=9000`,或改 `application.yml` 的 `server.port`。
>
> 上传大小上限:单文件 **100MB**(`FA_MAX_FILE_SIZE`)、整个请求 **120MB**(`FA_MAX_REQUEST_SIZE`),
> 可在 `application.yml` 或环境变量里覆盖;超 1MB 的请求体会直接落临时文件,不整个读进堆内存。

---

## 目录结构 / Project Layout

```
src/main/java/io/github/likeelysia/formalagent/
├── FormalAgentApplication.java   # Spring Boot 启动类
├── chat/       # ChatSession、Message —— 命令行时代的会话与消息(保留)
├── conversation/ # Conversation / ChatMessage / ConversationStore(JSON 落盘)/ ConversationService —— 多会话(对话框)
├── memory/     # ShortTermMemory(窗口裁剪)/ SessionSummarizer —— 短期记忆;LongTermMemory / MemoryStore / MemoryExtractor / MemoryCandidate —— 长期记忆(提炼 + 召回 + 治理)
├── cli/        # ChatCli(交互式命令行,CommandLineRunner)、IngestMain、ExtractMain、FileHint
├── config/     # AppConfig(@ConfigurationProperties)、SpringConfig —— 配置与容器装配
├── doc/        # DocumentReader / PlainTextReader / PdfReader(文字层+OCR) / ImageReader / TextSplitter / DocPipeline
├── extract/    # KnowledgeExtractor / ExtractService / IngestService / KnowledgeReport —— 提取与入库
├── llm/        # LlmClient、VisionClient、EmbeddingClient(+ 各自实现与 dto/)
├── knowledge/  # KnowledgeItem / KnowledgeStore / JsonKnowledgeStore —— 知识库与向量检索
├── ocr/        # OcrEngine 接口 + VisionOcrEngine(远程视觉) / RapidOcrEngine(本地 PP-OCR) + OcrEngines(选引擎/回退)
├── log/        # ApiLogger —— 模型调用审计日志
├── agent/      # Agent 主循环(ReAct)+ 工具(Tool 接口 / search_knowledge / knowledge_overview / get_knowledge_page)
├── service/    # AssistantService(一问一答的全流程)、ChatService(对话)、QaService(图片问答)、FileStorage(上传落盘)
├── web/        # Controller(REST + SSE 流式)+ SseStreamer + 统一异常处理
└── store/      # SessionStore —— 存档 / 读档 / 导出

src/main/resources/static/
├── index.html / style.css / app.js   # 单页问答界面(原生 JS,无框架)
└── vendor/     # 本地第三方库(离线可用,不走 CDN)
    ├── katex/  # 公式排版(katex.min.js + css + fonts/*.woff2)
    └── marked.min.js                 # Markdown 解析
```

配置在 `src/main/resources/application.yml`(前缀 `fa.*`);
前端页面在 `src/main/resources/static/`(`index.html` / `app.js` / `style.css`,
联系页 `contact.html` + 二维码 `img/wechat.jpg`);
提示词在 `src/main/resources/prompts/`。

---

## 关于密钥 / About Secrets

本项目会调用大模型 API。**仓库中不会包含任何 API Key**:

- 密钥通过**环境变量**提供(`DEEPSEEK_API_KEY` / `MOONSHOT_API_KEY`);
- `.gitignore` 已屏蔽 `.env`、`*.local.properties`、`secrets/`、`*.pem`、`*.key` 等敏感文件;
- 知识库数据(`knowledge/`)、上传文件(`data/`)与调用日志(`logs/`)同样不入库。

This project calls LLM APIs. **No API keys are committed**: keys come from environment
variables, and `.gitignore` blocks common secret files as well as generated data.

---

## 路线图 / Roadmap

- [x] 打通 HTTP 与 DeepSeek(Jackson 解析)
- [x] 多轮对话 + 会话存档 + 配置外置 + 调用日志
- [x] 文档 → 知识点提取流水线
- [x] Spring IoC 接入 + 统一 ObjectMapper / DTO
- [x] 结构化输出(JSON mode)
- [x] 超时 + 重试 + 退避(Spring Retry)
- [x] 知识库(接口 `KnowledgeStore`)+ 入库流水线(带页码出处)
- [x] 扫描版 PDF / 图片 OCR(远程视觉模型 + **本地 RapidOCR** 可切换,支持差页回退)
- [x] 图片问答(识图 → 检索 → 带出处回答)
- [x] **真检索:embedding + 余弦相似度 top-K**(本地 llama.cpp)
- [x] **Spring Boot 化:可执行 jar / `@ConfigurationProperties` / profile**
- [x] **日志规范(SLF4J/Logback,按天滚动)**
- [x] **HTTP 接口(REST:问答 / 入库 / 知识库 / 健康检查)**
- [x] **入库接口改为文件上传**(堵死“任意文件读取 / 路径遍历”)
- [x] **Agent 化:工具调用(Function Calling)+ ReAct 循环**
- [x] **工具集**:`search_knowledge` / `knowledge_overview` / `get_knowledge_page` / `calculator` / `unit_convert`
- [x] **hybrid 检索(向量 + 关键词)+ 相似度阈值**
- [x] **接口支持图片**(multipart 上传)
- [x] **多图提问**(一次最多 10 张;上传前浏览器内自动压图,缩略图可逐张删除)
- [x] **管理接口鉴权**(`X-Admin-Key`)
- [x] **Prompt 模板外置**(`resources/prompts/*.txt`)
- [x] **知识库来源管理:按来源删除 / 重灌**(CLI `--list/--remove-source/--replace` + REST `DELETE /api/knowledge/source`)
- [x] **多会话(对话框)+ 会话管理**:默认对话 + 新建/改名/删除/切换,记忆默认互相隔离(REST `/api/conversations`)
- [x] **多轮上下文(短期记忆)**:最近 N 轮原文 + 超窗口的**滚动摘要**,模型终于“记得”上一句(`fa.memory.short-term.*`)
- [x] **长期记忆**:后台提炼(规则预筛 + 模型打分)+ 相似度召回 top-K,按记忆域(`scopeId`)分桶(`fa.memory.long-term.*`)
- [x] **记忆治理**:看得见 / 改得动 / 删得掉(前端「🧠 记忆」抽屉 + `/api/memory`)+ 钉住(`pin`)不淘汰 + 总量上限淘汰最久没用的 + 导出
- [ ] 更多工具(联网搜索 —— 需配 Prompt Injection 防护)
- [ ] 向量库(知识到 10 万条量级再上)

---

## 许可证 / License

[MIT](./LICENSE) © 2026 likeelysia
