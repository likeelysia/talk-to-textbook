# 面向 10 万知识点的检索减负记录

日期：2026-10-25  
状态：**本地验证完成；新 JAR 待鹏哥手动更新，服务器当前仍运行 `2eb600a`**

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

`/api/knowledge?sample=...` 也改为使用 `count()`、`sources()`、`countsBySource()`、`page()`，不再先复制全库。

## 2. 规模测量

### 2.1 数据与方法

- 合成知识点：`synthetic|i`，1024 维 bge-m3 形状向量；用于压测，不代表真实教材内容。
- 每档都写入 `base.json` + `base.vectors.json`，应用从磁盘加载后测 `/api/health`。
- 环境：Windows 11，Temurin 17.0.20.1，`-Xms128m -Xmx768m`。
- 内存口径：Java 进程 `PrivateMemorySize64`；启动峰值 = 到健康检查通过前的最大采样，稳定值 = 健康后 5 秒。
- 查询延迟：单独 Java harness，固定 1024 维假 embedding，只测 `JsonKnowledgeStore.search()` 的扫描/排序；**不含真实 embedding HTTP 和 LLM**。20 次查询，8 条 Top-K。

### 2.2 资源与延迟

| 知识点 | base.json | vectors.json | 启动峰值 | 稳定占用 | 加载耗时 | 查询 p50 | 查询 p95 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 1.9 MB | 102.6 MB | 457.7 MB | 380.9 MB | 1.22 s | 13 ms | 17 ms |
| 30,000 | 5.8 MB | 307.8 MB | 616.6 MB | 541.7 MB | 2.59 s | 38 ms | 42 ms |
| 100,000 | 19.3 MB | 1,026.0 MB | 949.3 MB | 889.0 MB | 7.30 s | 124 ms | 135 ms |

结论：

- 10 万条在本地 `-Xmx768m` 能启动并完成检索，稳定约 **889 MB**，但已经接近 768 MB 堆上限的实际边界。
- 服务器 systemd 限制是 `MemoryMax=1.28 GB`；100k 的 889 MB 只是本地 Windows 进程口径，不能直接等同于 Linux cgroup 的 `MemoryCurrent`。**上线前必须在服务器上实测**，不能仅凭这张表宣布 2G 可承载 10 万条。
- 向量 JSON 文件本身约 1.0 GB；Java 堆里是 `float[1024]` 常驻，JSON 文件大小、进程内存、cgroup 内存不是同一个概念。

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
TOTAL=443 FAIL=0 ERR=0 SKIP=0
```

- 新增：批量补齐可恢复测试、查询不自动补齐测试、count/page/get 测试。
- 本轮本地 embedding 开启，`RetrievalEvalTest` 真实评测执行，没有被跳过。

## 5. 发布产物

- 路径：`dist/talk-to-textbook-0.1.0-e04b5a1.jar`
- 大小：97,020,893 字节
- SHA-256：
  ```text
  0FFBD026C8BFADD28D6015D9732DE23838F34300B0E23A30FA497D96DBE99D5F
  ```
- 源码提交：`e04b5a1 perf(knowledge): 查询减负与显式向量补齐`

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

- 10 万条合成数据在本地 768 MB 堆下可启动，稳定 889 MB，但服务器 cgroup 余量必须实测；
- 真实质量回归已通过，目前没有证据表明必须上 SQLite/向量库；
- 若服务器实测出现 MemoryHigh 持续节流、接近 MemoryMax、OOM 或延迟不可接受，再按证据选择按需读盘、分片索引或远程检索；
- 不直接购买远程数据库，也不把整机内存上限调高来掩盖问题。
