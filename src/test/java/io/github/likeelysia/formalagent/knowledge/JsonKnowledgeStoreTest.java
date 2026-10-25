package io.github.likeelysia.formalagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonKnowledgeStoreTest {

    @TempDir Path tmp;
    private final ObjectMapper mapper = new ObjectMapper();
    private Path file;

    /**
     * 假 embedder:按"意思分组"给固定向量 —— 用来验证"换说法也能命中"(真模型在集成环境测)。
     * 位移/移动/位置差 → (1,0);线程/执行单元 → (0,1);其余 → (0.2,0.2)。
     */
    private static final EmbeddingClient FAKE = text -> {
        if (text.matches(".*(位移|挪动|位置差).*")) return new float[]{1f, 0f};
        if (text.matches(".*(线程|执行单元).*")) return new float[]{0f, 1f};
        return new float[]{0.2f, 0.2f};
    };

    private JsonKnowledgeStore store;

    @BeforeEach
    void setUp() {
        file = tmp.resolve("base.json");
        store = new JsonKnowledgeStore(mapper, FAKE, file.toString(), 0);
    }

    private static KnowledgeItem item(String id, String name, String detail) {
        return new KnowledgeItem(id, name, detail, "book.md", null, "text");
    }

    @Test
    @DisplayName("加入后能列出")
    void addAndList() {
        store.addAll(List.of(item("1", "IoC", "控制反转")));
        assertEquals(1, store.all().size());
    }

    @Test
    @DisplayName("落盘后重新加载仍在(持久化)")
    void persistsAndReloads() {
        store.addAll(List.of(item("1", "IoC", "控制反转")));

        JsonKnowledgeStore reloaded = new JsonKnowledgeStore(mapper, FAKE, file.toString(), 0);
        assertEquals(1, reloaded.all().size());
        assertEquals("IoC", reloaded.all().get(0).name());
    }

    @Test
    @DisplayName("查询不会自动补向量;显式分批补齐可恢复且落盘")
    void queryDoesNotBackfillAndBackfillIsResumable() {
        store.addAll(List.of(
                item("1", "位移", "质点从A到B位置矢量的增量"),
                item("2", "线程", "进程里的执行单元")));
        assertEquals(2, store.missingVectorCount(), "addAll 不应再自动算向量");

        store.search("位移", 5);
        assertEquals(2, store.missingVectorCount(), "搜索路径不应隐式补齐全库");

        var first = store.backfillVectors(1);
        assertEquals(1, first.processed());
        assertEquals(1, first.remaining());
        assertTrue(!first.complete());

        var second = store.backfillVectors(1);
        assertEquals(1, second.processed());
        assertEquals(0, second.remaining());
        assertTrue(second.complete());
        assertEquals(0, store.missingVectorCount());

        JsonKnowledgeStore reloaded = new JsonKnowledgeStore(mapper, FAKE, file.toString(), 0);
        assertEquals(0, reloaded.missingVectorCount(), "每批补齐后应已写入 sidecar");
    }

    @Test
    @DisplayName("落盘失败不假装完成;故障解除后重试真的写入")
    void backfillFailureDoesNotFakeCompletion() throws Exception {
        store.addAll(List.of(item("1", "位移", "位置变化")));
        Path sidecar = Path.of(file.toString().replaceAll("\\.json$", "") + ".vectors.json");
        Files.createDirectory(sidecar);                 // 故意让 sidecar 路径不可写

        assertThrows(AgentException.class, () -> store.backfillVectors(1));
        assertEquals(1, store.missingVectorCount(), "失败后内存仍应保持未补齐");

        Files.delete(sidecar);                          // 解除障碍
        var result = store.backfillVectors(1);
        assertTrue(result.complete());
        assertTrue(Files.isRegularFile(sidecar), "重试必须真的写出 sidecar");
        assertEquals(0, store.missingVectorCount());

        JsonKnowledgeStore reloaded = new JsonKnowledgeStore(mapper, FAKE, file.toString(), 0);
        assertEquals(0, reloaded.missingVectorCount(), "重启后应从 sidecar 读到向量");
    }

    @Test
    @DisplayName("慢补齐不阻塞普通查询")
    void slowBackfillDoesNotBlockSearch() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EmbeddingClient slowBackfill = new EmbeddingClient() {
            @Override public float[] embed(String text) {
                return new float[]{1f, 0f};
            }
            @Override public List<float[]> embedAll(List<String> texts) {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return texts.stream().map(t -> new float[]{1f, 0f}).toList();
            }
        };
        Path slowFile = tmp.resolve("slow.json");
        JsonKnowledgeStore slow = new JsonKnowledgeStore(mapper, slowBackfill, slowFile.toString(), 0);
        slow.addAll(List.of(item("1", "IoC", "控制反转")));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> backfill = pool.submit(() -> slow.backfillVectors(1));
            assertTrue(started.await(1, TimeUnit.SECONDS), "补齐应已进入慢 embedding");
            long t0 = System.nanoTime();
            List<KnowledgeItem> hits = slow.search("控制反转", 5);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 500, "慢补齐期间查询不应被锁住,实际 " + ms + "ms");
            assertEquals(1, hits.size());
            release.countDown();
            backfill.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("补齐期间条目被删除:不提交过期向量")
    void deletedDuringBackfillIsNotCommitted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EmbeddingClient slowBackfill = new EmbeddingClient() {
            @Override public float[] embed(String text) { return new float[]{1f, 0f}; }
            @Override public List<float[]> embedAll(List<String> texts) {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return texts.stream().map(t -> new float[]{1f, 0f}).toList();
            }
        };
        JsonKnowledgeStore slow = new JsonKnowledgeStore(mapper, slowBackfill,
                tmp.resolve("stale.json").toString(), 0);
        slow.addAll(List.of(item("1", "IoC", "控制反转")));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> backfill = pool.submit(() -> slow.backfillVectors(1));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            slow.removeBySource("book.md");
            release.countDown();
            backfill.get(2, TimeUnit.SECONDS);
            assertTrue(slow.get("1") == null);
            assertEquals(0, slow.missingVectorCount(), "删除后没有条目需要补向量");
            JsonKnowledgeStore reloaded = new JsonKnowledgeStore(mapper, FAKE,
                    slowFileOf("stale.json").toString(), 0);
            assertEquals(0, reloaded.count(), "重启后不应留下被删条目的向量/条目");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Path slowFileOf(String name) {
        return tmp.resolve(name);
    }

    @Test
    @DisplayName("默认阈值下,缺向量条目按单条关键词回退")
    void missingVectorItemFallsBackToKeywordAtDefaultThreshold() {
        JsonKnowledgeStore s = new JsonKnowledgeStore(mapper,
                text -> text.contains("无关") ? new float[]{0f, 1f} : new float[]{1f, 0f},
                tmp.resolve("threshold.json").toString(), 0.45);
        s.addAll(List.of(
                item("1", "sample", "精确关键词 sample"),
                item("2", "other", "另一条内容")));

        assertEquals("sample", s.search("sample", 5).get(0).name(),
                "全部缺向量时,精确关键词应能过默认 0.45");
        s.backfillVectors(1);                       // 只补第一条
        assertEquals(1, s.missingVectorCount());
        assertEquals("other", s.search("other", 5).get(0).name(),
                "部分缺向量时,缺向量条目仍应按关键词命中");
        assertTrue(s.search("完全无关的问题", 5).isEmpty(),
                "关键词回退不能把无关问题硬塞进来");
    }

    @Test
    @DisplayName("并发补齐单任务准入:第二次快速 busy,不重复调用 embedding")
    void concurrentBackfillIsSingleFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient slow = new EmbeddingClient() {
            @Override public float[] embed(String text) { return new float[]{1f, 0f}; }
            @Override public List<float[]> embedAll(List<String> texts) {
                calls.incrementAndGet();
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return texts.stream().map(t -> new float[]{1f, 0f}).toList();
            }
        };
        JsonKnowledgeStore s = new JsonKnowledgeStore(mapper, slow,
                tmp.resolve("singleflight.json").toString(), 0);
        s.addAll(List.of(item("1", "IoC", "控制反转")));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> s.backfillVectors(1));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertThrows(KnowledgeBackfillBusyException.class, () -> s.backfillVectors(1));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            assertEquals(1, calls.get(), "同一条只应计算一次");
            assertEquals(0, s.missingVectorCount());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("补齐异常会解除在途状态,后续重试成功")
    void failedBackfillReleasesSingleFlightGate() {
        AtomicBoolean first = new AtomicBoolean(true);
        EmbeddingClient flaky = new EmbeddingClient() {
            @Override public float[] embed(String text) { return new float[]{1f, 0f}; }
            @Override public List<float[]> embedAll(List<String> texts) {
                if (first.getAndSet(false)) throw new IllegalStateException("模拟网络失败");
                return texts.stream().map(t -> new float[]{1f, 0f}).toList();
            }
        };
        JsonKnowledgeStore s = new JsonKnowledgeStore(mapper, flaky,
                tmp.resolve("flaky.json").toString(), 0);
        s.addAll(List.of(item("1", "IoC", "控制反转")));

        assertThrows(AgentException.class, () -> s.backfillVectors(1));
        assertTrue(s.backfillVectors(1).complete(), "异常释放后应能重试成功");
        assertEquals(0, s.missingVectorCount());
    }

    @Test
    @DisplayName("同 id 覆盖,不重复")
    void sameIdOverwrites() {
        store.addAll(List.of(item("1", "IoC", "旧")));
        store.addAll(List.of(item("1", "IoC", "新")));
        assertEquals(1, store.all().size());
        assertEquals("新", store.all().get(0).detail());
    }

    @Test
    @DisplayName("向量检索:换个说法也能命中(字面并不相同)")
    void vectorSearchMatchesParaphrase() {
        store.addAll(List.of(
                item("1", "位移", "质点从A到B位置矢量的增量"),
                item("2", "线程", "进程里的执行单元")));
        store.backfillVectors(10);

        // 查询"物体挪动前后的位置差" —— 与条目1的**字面完全不同**
        List<KnowledgeItem> hits = store.search("物体挪动前后的位置差", 5);

        assertEquals("位移", hits.get(0).name(), "相关的应排第一");
    }

    @Test
    @DisplayName("对照组:同一查询,关键词版 0 命中(证明向量确实在干活)")
    void keywordVersionMissesTheSameParaphrase() {
        JsonKnowledgeStore keywordOnly = new JsonKnowledgeStore(mapper,
                text -> {
                    throw new IllegalStateException("服务没开");
                },
                file.toString(), 0);
        keywordOnly.addAll(List.of(
                item("1", "位移", "质点从A到B位置矢量的增量"),
                item("2", "线程", "进程里的执行单元")));

        assertTrue(keywordOnly.search("物体挪动前后的位置差", 5).isEmpty(), "字面不重 → 关键词搜不到");
    }

    @Test
    @DisplayName("降级:embedding 服务挂了 → 退回关键词匹配")
    void fallsBackToKeywordWhenEmbeddingDown() {
        JsonKnowledgeStore s = new JsonKnowledgeStore(mapper,
                text -> {
                    throw new IllegalStateException("服务没开");
                },
                file.toString(), 0);
        s.addAll(List.of(
                item("1", "IoC", "控制反转,把创建对象的权力交给容器"),
                item("2", "线程", "进程里的执行单元")));

        List<KnowledgeItem> hits = s.search("控制反转", 5);   // 关键词能匹配到

        assertEquals(1, hits.size());
        assertEquals("IoC", hits.get(0).name());
    }

    @Test
    @DisplayName("阈值:相似度不够就当没找到(避免硬塞不相干内容)")
    void thresholdFiltersWeakMatches() {
        // 条目向量 (1,0);问“另一个话题”向量与之成 60° → cos=0.5 → hybrid 分 = 0.7×0.5 + 0.3×0 = 0.35
        EmbeddingClient angled = text ->
                text.contains("位移") ? new float[]{1f, 0f} : new float[]{0.5f, 0.866f};

        JsonKnowledgeStore loose = new JsonKnowledgeStore(mapper, angled,
                tmp.resolve("loose.json").toString(), 0.3);
        loose.addAll(List.of(item("1", "位移", "位置变化")));
        loose.backfillVectors(10);
        assertEquals(1, loose.search("另一个话题", 5).size(), "阈值 0.3 → 0.35 分应保留");

        JsonKnowledgeStore strict = new JsonKnowledgeStore(mapper, angled,
                tmp.resolve("strict.json").toString(), 0.5);
        strict.addAll(List.of(item("1", "位移", "位置变化")));
        strict.backfillVectors(10);
        assertTrue(strict.search("另一个话题", 5).isEmpty(), "阈值 0.5 → 0.35 分该被过滤");
    }

    @Test
    @DisplayName("hybrid:关键词命中会把名次顶上去")
    void hybridRewardsKeywordHits() {
        EmbeddingClient flat = text -> new float[]{1f, 0f};    // 向量分完全相同 → 名次只能由关键词那一路决定
        JsonKnowledgeStore store = new JsonKnowledgeStore(mapper, flat,
                tmp.resolve("hybrid.json").toString(), 0);
        store.addAll(List.of(
                item("1", "路程", "质点走过的路径长度"),
                item("2", "线程", "进程里的执行单元")));
        store.backfillVectors(10);

        List<KnowledgeItem> hits = store.search("路程", 5);

        assertEquals("路程", hits.get(0).name(), "字面命中的应排前面(关键词那 0.3 是关键)");
    }

    @Test
    @DisplayName("向量模型指纹不匹配:旧向量不加载,关键词检索仍可用")
    void vectorModelMismatchFallsBackToKeyword() {
        store.addAll(List.of(
                item("1", "IoC", "控制反转,把创建对象的权力交给容器"),
                item("2", "线程", "进程里的执行单元")));
        store.backfillVectors(10);

        EmbeddingClient differentModel = new EmbeddingClient() {
            @Override public float[] embed(String text) {
                throw new IllegalStateException("模型不匹配时不应调用");
            }
            @Override public String modelName() {
                return "different-model";
            }
        };
        JsonKnowledgeStore reloaded = new JsonKnowledgeStore(mapper, differentModel, file.toString(), 0);

        assertEquals(2, reloaded.all().size(), "知识点本体仍应加载");
        assertEquals(1, reloaded.search("控制反转", 5).size(), "旧向量作废后应退回关键词");
    }

    // ---------------------------------------------------------------- 删除 / 替换

    @Test
    @DisplayName("按来源删除:只动这本书,别的书不动,且落盘")
    void removeBySourceKeepsOtherSources() {
        store.addAll(List.of(
                new KnowledgeItem("a1", "A1", "d", "bookA.pdf", "第 1 页", "pdf"),
                new KnowledgeItem("a2", "A2", "d", "bookA.pdf", "第 2 页", "pdf"),
                new KnowledgeItem("b1", "B1", "d", "bookB.pdf", "第 1 页", "pdf")));

        int removed = store.removeBySource("bookA.pdf");

        assertEquals(2, removed, "bookA 的 2 条应被删");
        assertEquals(List.of("B1"), store.all().stream().map(KnowledgeItem::name).toList());

        JsonKnowledgeStore reloaded = new JsonKnowledgeStore(mapper, FAKE, file.toString(), 0);
        assertEquals(1, reloaded.all().size(), "删除应已落盘");
    }

    @Test
    @DisplayName("按来源删除:空参数/不存在的来源 = 什么都不删(防手滑清库)")
    void removeBySourceRefusesBlank() {
        store.addAll(List.of(new KnowledgeItem("a1", "A1", "d", "bookA.pdf", null, "pdf")));

        assertEquals(0, store.removeBySource(null));
        assertEquals(0, store.removeBySource("   "));
        assertEquals(0, store.removeBySource("不存在的书.pdf"));
        assertEquals(1, store.all().size(), "知识库必须纹丝不动");
    }

    @Test
    @DisplayName("重灌(替换):旧条目整份换掉,刚被删的知识点不会残留")
    void replaceSourceDropsStaleItems() {
        store.addAll(List.of(
                new KnowledgeItem("book.md|K1", "K1", "旧", "book.md", "第 1 页", "text"),
                new KnowledgeItem("book.md|K2", "K2", "旧", "book.md", "第 1 页", "text"),
                new KnowledgeItem("other.md|X", "X", "别家的", "other.md", "第 1 页", "text")));

        int written = store.replaceSource("book.md", List.of(
                new KnowledgeItem("book.md|K1", "K1", "新", "book.md", "第 1 页", "text")));

        assertEquals(1, written);
        assertEquals(2, store.all().size(), "book.md 只剩 1 条 + other.md 的 1 条");
        KnowledgeItem k1 = store.all().stream()
                .filter(i -> "K1".equals(i.name())).findFirst().orElseThrow();
        assertEquals("新", k1.detail(), "K1 应换成新版内容");
        assertTrue(store.all().stream().anyMatch(i -> "X".equals(i.name())), "别的书不该被动");
        assertEquals(List.of("other.md", "book.md"), store.sources(), "被替换的重新排到末尾(LinkedHashMap 语义)");
    }

    @Test
    @DisplayName("来源统计:按来源计数、按首次出现排序")
    void countsBySource() {
        store.addAll(List.of(
                new KnowledgeItem("a1", "A1", "d", "bookA.pdf", null, "pdf"),
                new KnowledgeItem("b1", "B1", "d", "bookB.pdf", null, "pdf"),
                new KnowledgeItem("a2", "A2", "d", "bookA.pdf", null, "pdf")));

        assertEquals(Map.of("bookA.pdf", 2, "bookB.pdf", 1), store.countsBySource());
        assertEquals(List.of("bookA.pdf", "bookB.pdf"), store.sources());
    }

    @Test
    @DisplayName("删条目后向量表不留孤儿(免得 sidecar 越滚越大)")
    void removalDropsOrphanVectors() throws Exception {
        store.addAll(List.of(
                new KnowledgeItem("a1", "A1", "d", "bookA.pdf", null, "pdf"),
                new KnowledgeItem("b1", "B1", "d", "bookB.pdf", null, "pdf")));
        store.backfillVectors(10);
        assertEquals(2, vectorCount(), "显式补齐后应有 2 条向量");

        store.removeBySource("bookA.pdf");

        assertEquals(1, vectorCount(), "已删条目的向量也该跟着走");
    }

    /** 从 sidecar 文件里数向量条数。 */
    private int vectorCount() throws Exception {
        Path sidecar = Path.of(file.toString().replaceAll("\\.json$", "") + ".vectors.json");
        return mapper.readTree(Files.readString(sidecar, StandardCharsets.UTF_8))
                .get("vectors").size();
    }
}
