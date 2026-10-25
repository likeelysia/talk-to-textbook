# 知识库 JSON 流式读写优化记录

日期：2026-10-25  
状态：**已在服务器启用；旧版保留可回滚**

## 1. 改动范围

只改 `JsonKnowledgeStore` 的 JSON 读写路径：

- 知识点 `base.json`：从“整文件读成 String，再反序列化 List”改为 `MappingIterator` 逐条读、逐条放入 `items`。
- 向量 `base.vectors.json`：先流式读取 `model` 字段；模型匹配后，再按 `vectors` 的每个 id 直接读 `float[]`，不再先构造完整 `VectorFile` 中间对象。
- 写回：从 `writeValueAsString(...)` 再 `Files.writeString` 改为 Jackson 直接写 `OutputStream`，不再生成完整 JSON 字符串副本。
- 数据格式、字段名、原子写入语义、检索逻辑、现有数据文件均未改。

提交：`2eb600a perf(knowledge): JSON 知识库改为流式读写`

## 2. 对照环境

- 本机 Windows 11
- JDK：Temurin 17.0.20.1
- 参数：`-Xms128m -Xmx768m`
- 数据：同一份 **7287 条 / 两本教材** 的 `base.json` + `base.vectors.json`
- 旧 JAR：`talk-to-textbook-0.1.0-7829850.jar`
- 新 JAR：`talk-to-textbook-0.1.0-2eb600a.jar`
- 两边都启动到 `/api/health` 返回 200，`knowledgeCount=7287`
- 内存口径：Windows 进程 `PrivateMemorySize64`；启动峰值 = 到健康检查通过前的最大采样，稳定值 = 健康后静置 8 秒

> 这不是 Linux cgroup 口径，绝对值不能直接当服务器 `MemoryCurrent`；但两边环境、数据、JVM 参数相同，相对变化可作为判断依据。

## 3. 对照结果

| 版本 | 启动峰值 | 稳定占用 | 说明 |
|---|---:|---:|---|
| 旧 JAR `7829850` | **1118.5 MB** | **936.1 MB** | 整文件 `readString` + 完整反序列化 |
| 新 JAR `2eb600a` | **449.3 MB** | **360.7 MB** | 流式逐条读取 |

- 启动峰值下降约 **669 MB**
- 稳定占用下降约 **575 MB**
- 两边都加载了同样的 7287 条知识

服务器侧旧版现场占用（供对照）：约 **958 MB** 当前、**1.07 GB** 峰值；新版尚未部署，不能把本地数字直接写成服务器已达标。

## 4. 检索结果对照

同一问题：

```text
英语写作中主题句有什么作用？
```

- 旧 JAR：HTTP 200，引用《新视野大学英语（第四版）读写3》第 122 页
- 新 JAR：HTTP 200，引用同一本教材第 122 页
- 两边的检索工具轨迹都包含 `search_knowledge` 和 `get_knowledge_page`，没有出现“新版查不到/旧版能查到”的明显回退
- 模型回答措辞和工具调用次数不完全相同，这是模型生成的非确定性，不是检索结果差异

局限：只做了这一条真实模型对照，不能宣称所有问题逐字一致；单元测试覆盖了向量检索、关键词降级、模型指纹不匹配等行为。

## 5. 测试结果

JDK17 全量：

```text
TOTAL=440 FAIL=0 ERR=0 SKIP=1
```

- 新增 1 条“向量模型指纹不匹配时退回关键词”测试
- 跳过项：`RetrievalEvalTest`（缺少真实评测知识数据，不是代码失败）

## 6. 发布产物

- 路径：`dist/talk-to-textbook-0.1.0-2eb600a.jar`
- 大小：97,017,097 字节
- SHA-256：

```text
72A3473AC3523B10B94E0E9E0433221B02D6F2B50BCBA1FA6138126A2F776CD4
```

## 7. 服务器更新结果

已于 2026-10-25 在服务器完成更新；数据目录未改，旧版目录保留。

实际步骤：

```bash
sudo install -d -m 0755 /opt/lamplit/releases/2eb600a
sudo install -o root -g root -m 0644 /home/admin/talk-to-textbook-0.1.0-2eb600a.jar \
  /opt/lamplit/releases/2eb600a/app.jar
sudo ln -sfn /opt/lamplit/releases/2eb600a /opt/lamplit/current
sudo systemctl restart lamplit
```

实际结果：

```text
readlink -f /opt/lamplit/current
/opt/lamplit/releases/2eb600a

systemctl is-active lamplit
active

/api/health
{"status":"UP","degraded":true,"knowledgeCount":7287,
 "embedding":{"status":"DOWN","model":"bge-m3"},
 "vision":{"status":"UP","provider":"Kimi","model":"kimi-k2.6"}}

MemoryCurrent=355065856
MemoryPeak=355319808
```

- 服务器 `MemoryCurrent` 约 **355.1 MB**，峰值约 **355.3 MB**；旧版约 **958 MB / 1.07 GB**。
- 数据没有改格式；两本教材 7287 条和生词/笔记/记忆均保留。
- 更新过程中曾出现一次“切了链接但 JAR 未安装”的短暂失败，已先切回 `7829850` 恢复，再按“先装 JAR、后切链接”的顺序完成；旧版仍在，可回滚。
- 浏览器会话会因重启失效，需要重新登录；这不影响数据。

## 8. 回滚

```bash
sudo ln -sfn /opt/lamplit/releases/7829850 /opt/lamplit/current
sudo systemctl restart lamplit
```

旧 JAR：`dist/talk-to-textbook-0.1.0-7829850.jar`  
旧 JAR SHA-256：`61C7793C3EA5476BF15923B913D36C14B742844731EA8959E770F15BA6B5144D`

数据格式没有变化；如果新版运行后写入了数据，回滚前仍应先备份，避免新数据被旧版本覆盖。

## 9. 结论与边界

- 流式读写的主要收益是**去掉了整文件字符串和完整中间对象**，所以启动峰值和稳定占用明显下降。
- 最终常驻 `items` / `vectors` 两个 Map 仍然存在；这不是“按需读盘”，也不能把 7287 条的常驻成本变成 0。
- 如果新版在服务器上的稳定占用仍接近 `MemoryHigh`，下一步才考虑按需读取、分片索引或外部检索；本轮不调高到整机 2 GB，也不购买远程数据库。
