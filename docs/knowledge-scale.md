# 面向 10 万知识点的检索减负记录

日期：2026-10-25  
状态：**P2 返工完成，本地测试通过；新 JAR 待鹏哥手动更新，服务器当前仍运行 `2eb600a`**

## 1. 本轮改动

### 1.1 查询路径不再自动补齐向量

- `JsonKnowledgeStore.search()` 删除了 `if (vectors.size() < items.size()) ensureVectors()`。
- 查询只使用已有向量；缺向量时按关键词检索，不再在用户提问时触发全库 embedding。
- `addAll()` 和 `replaceSource()` 也不再自动生成向量。
- 新增显式管理接口：
  ```text
  GET  /api/knowledge/vectors/status          # total / missing / complete
  POST /api/knowledge/vectors/backfill?limit=200
  ```
  两个接口都受 `X-Admin-Key` 保护。`backfill` 每批计算后立即写 sidecar；返回 `processed / remaining / total / complete`，中断后再调用会从剩余条目继续，不需要重算已完成批次。
- `/api/health` 新增 `missingVectors`，可直接看出是否还有未补向量的条目。

### 1.2 Top-K 筛选

- 从“全库命中装进 List，再全排序”改为 **大小为 topK 的小顶堆**，O(N log K)。
- 同分时按原条目顺序稳定输出，避免相同分数下结果随机抖动。
- `topK <= 0` 直接返回空。

### 1.3 数量、分页、按 ID

新增接口：

```text
GET /api/knowledge/count                         # 只查总数
GET /api/knowledge/page?offset=0&limit=20        # 分页,limit 上限 200
GET /api/knowledge/item?id=<稳定ID>              # 单条,找不到 404
```

`/api/knowledge?sample=...` 也改为使用 `count()`、`sources()`、`countsBySource()`、`page()`，不再先复制全库。删除来源后的响应也改用 `count()`。

### 1.4 P2-1/P2-2 返工

- **P2-1 落盘失败恢复**：补齐批次先写临时 sidecar，`Files.move(..., ATOMIC_MOVE)` 成功后才把向量提交到内存；失败时旧 sidecar 和内存的 missing 状态都保持不变。临时文件写失败会清理，不破坏上一份完成的 sidecar。
- **P2-2 慢补齐不堵查询**：
  - `embedAll` 和 sidecar 写入都在 `stateLock` 外执行；
  - 查询只在短临界区内扫描内存，不再被后台补齐的 HTTP 锁住；
  - `writeLock` 只串行化导入/提交/写盘，不与普通查询共用；
  - 提交前按 `KnowledgeItem.equals` 校验条目仍在且未被替换，不提交已删除/替换条目的过期向量；
  - 没有引入无界线程或并行任务，补齐仍是单批次串行。
- `backfill` 默认批量从 50 调到 **1000**，上限 2000。

### 1.5 复验后的两项修复

- **缺向量按条回退**：评分不再只看查询 embedding 是否成功；当前条目没有向量时，直接使用该条的 `keywordScore`，不再算成 `0.3×关键词分` 而被默认 `min-score=0.45` 误杀。向量完整的条目仍走原混合评分。
- **补齐单任务准入**：`backfillVectors` 用独立 `AtomicBoolean` 覆盖“选批 + 远程计算 + 提交”；已有任务时第二次调用快速抛出 `KnowledgeBackfillBusyException`，HTTP 映射为 **409**，不排队、不重复调用 embedding。异常/写盘失败在 `finally` 释放准入，后续可重试。查询、删除、替换不拿这把准入锁。

## 2. 规模测量

### 2.1 数据与方法

- 合成知识点：`synthetic|i`，1024 维 bge-m3 形状向量；用于压测，不代表真实教材内容。
- 每档都写入 `base.json` + `base.vectors.json`，应用从磁盘加载后测 `/api/health`。
- 环境：Windows 11，Temurin 17.0.20.1，`-Xms128m -Xmx768m`。
- 内存口径：Java 进程 `PrivateMemorySize64`；启动峰值 = 到健康检查通过前的最大采样，稳定值 = 健康后 5 秒。
- 查询延迟：单独 Java harness，固定 1024 维假 embedding，只测 `JsonKnowledgeStore.search()` 的扫描/排序；**不含真实 embedding HTTP 和 LLM**。20 次查询，8 条 Top-K。

### 2.2 资源与延迟

多次运行有 Windows/JVM/本地 embedding CPU 竞争导致的波动，下面给范围，不把单次值当精确常数：

| 知识点 | 启动峰值 | 稳定占用 | 加载耗时 | 查询 p50 | 查询 p95 |
|---:|---:|---:|---:|---:|---:|
| 10,000 | 457.7–614.4 MB | 380.9–527.5 MB | 1.16–1.22 s | 13 ms | 17 ms |
| 30,000 | 605.5–616.6 MB | 530.3–541.7 MB | 2.54–2.59 s | 38–43 ms | 42–48 ms |
| 100,000 | 949.3–1084.7 MB | 889.0–1018.1 MB | 7.30–9.50 s | 124–167 ms | 135–171 ms |

结论：

- 10 万条在本地 `-Xmx768m` 能启动并完成检索，进程稳定占用约 **0.9–1.0 GB**；**本轮没有记录堆 used/max**，所以不能拿 `PrivateMemorySize64` 与 768 MB 堆上限直接比较、推断堆余量。
- 服务器 systemd 限制是 `MemoryMax=1.28 GB`；100k 的本地进程值不能直接等同于 Linux cgroup 的 `MemoryCurrent`。**上线前必须在服务器上实测**，不能仅凭这张表宣布 2G 可承载 10 万条。
- 查询 p50 在 124–167 ms 之间波动，主要受本地 embedding 进程占 CPU 影响；这是存储层检索，不含真实 embedding HTTP 和 LLM。
- 100k 的 `items`/`vectors` 仍全量常驻，检索扫描仍在 `stateLock` 内串行；本轮只是把网络/写盘移出查询锁，**不代表已具备多人并发容量**。
- 向量 JSON 文件本身约 1.0 GB；Java 堆里是 `float[1024]` 常驻，JSON 文件大小、进程内存、cgroup 内存不是同一个概念。

### 2.3 补齐写放大与导入压力

补齐每批会完整重写 sidecar；这是在“可恢复”和“写入放大量”之间的取舍，不能只看查询延迟。

实测（假 embedding，快速返回；只测存储层写入，不含真实 API 费用）：

| 数据量 | 批量 | 批数 | 耗时 | 最终 sidecar | 累计写入 | 单次最大 |
|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 1000 | 10 | 2.17 s | 41.1 MB | 226.3 MB | 41.1 MB |
| 10,000 | 100 | 100 | 15.19 s | 41.1 MB | 2.08 GB | 41.1 MB |
| 30,000 | 1000 | 30 | 15.23 s | 123.5 MB | 1.91 GB | 123.5 MB |

- 10k/1000 的累计写入约是最终 sidecar 的 5.5 倍；10k/100 则约 50 倍。批量越小，重写次数越多。
- 按此线性估算，100k/1000 约 100 批、最终 sidecar 约 411 MB、累计写入约 20.8 GB；这是**估算**，没有实跑 100k 补齐。
- 写入过程中会多占一个临时 sidecar 的磁盘空间；磁盘规划要按“旧 sidecar + 新 sidecar”留余地，但旧文件在原子替换前不会被破坏。
- 这仍不是增量日志；若导入压力不可接受，下一步应设计增量/检查点合并，而不是继续缩小批量。
- 表中 10k 最终 sidecar 41.1 MB，而 10k 完整 `base.vectors.json` 约 102.6 MB；差异来自假向量取值/序列化格式（harness 写紧凑 JSON，原数据是不同格式）。因此 100k 的 411 MB 只是同口径估算，**不能当作真实 bge-m3 sidecar 的确定大小**。
- 每批 1000 只是降低整文件重写次数；`LocalEmbeddingClient` 实际仍按每 16 条发一个 HTTP 请求。上面的秒数是假 embedding 的纯存储耗时，不代表真实模型/代理超时下的补齐耗时；上线时要用代表性小批次实测。
- 慢补齐期间的普通查询已在单测中验证：模拟 `embedAll` 等待时，查询仍能在 500 ms 内返回；并发补齐的第二个请求会快速得到 409，而不是重复调用 embedding。

## 3. 真实检索质量回归

使用项目现有 `RetrievalEvalTest`，本地 embedding 8090 开启，真实 12,925 条教材数据、`minScore=0.45`：

```text
可回答题 41 | 命中@5 35 | recall@5 0.85 | MRR 0.77 | 页命中@5 0.88
应拒答题 5 | 正确拒答 5 | 拒答率 1.00
```

现有门槛是 recall ≥ 0.80、MRR ≥ 0.70、页命中 ≥ 0.80、拒答 ≥ 0.90，全部通过。  
这条是**真实教材与真实 bge-m3**；10k/30k/100k 的延迟表是合成数据，两者不要混为一谈。

## 4. 测试

```text
TOTAL=450 FAIL=0 ERR=0 SKIP=0
```

- 新增：批量补齐可恢复测试、查询不自动补齐测试、count/page/get 测试、落盘失败重试测试、慢补齐并发查询测试、补齐期间删除条目不提交测试、默认阈值缺向量回退测试、并发补齐 409/异常释放测试。
- 本轮本地 embedding 开启，`RetrievalEvalTest` 真实评测执行，没有被跳过。

## 5. 发布产物

- 路径：`dist/talk-to-textbook-0.1.0-0709e5e.jar`
- 大小：97,023,651 字节
- SHA-256：
  ```text
  989044C83B8A943158D399DE65A8571973D554F2D854A8E0EC47A177524861BA
  ```
- 源码提交：`0709e5e fix(knowledge): 缺向量按条回退并限制补齐并发`
- 前一个候选 `7cb4643` 仅作为复验对照，不作为最终交付包。
- 前一个候选 `e04b5a1` 仅作为对照，不作为交付包。

## 6. 服务器状态与更新边界

- 服务器当前仍运行 `2eb600a`（7287 条、约 355 MB），**本轮没有自动更新**。
- 新 JAR 由鹏哥手动备份、上传、切链接和验证；旧版 `2eb600a`、`7829850` 目录保留。
- 更新后如果 `knowledgeCount` 正常但 `missingVectors > 0`，需要显式调用补向量接口；不要用提问来“顺手补齐”。

## 7. 回滚

```bash
sudo ln -sfn /opt/lamplit/releases/2eb600a /opt/lamplit/current
sudo systemctl restart lamplit
```

更早的回滚点：

```bash
sudo ln -sfn /opt/lamplit/releases/7829850 /opt/lamplit/current
sudo systemctl restart lamplit
```

数据格式未变；但新版写入的知识/向量仍应先备份再回滚。

## 8. 数据库迁移结论

本轮**不迁移数据库**：

- 10 万条合成数据在本地 `-Xmx768m` 进程稳定约 0.9–1.0 GB 可启动，但堆 used/max 未测，服务器 cgroup 余量必须实测；
- 真实质量回归已通过，目前没有证据表明必须上 SQLite/向量库；
- 若服务器实测出现 MemoryHigh 持续节流、接近 MemoryMax、OOM 或延迟不可接受，再按证据选择按需读盘、分片索引或远程检索；
- 不直接购买远程数据库，也不把整机内存上限调高来掩盖问题。
