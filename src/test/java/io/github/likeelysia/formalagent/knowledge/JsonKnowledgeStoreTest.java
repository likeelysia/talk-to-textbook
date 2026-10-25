package io.github.likeelysia.formalagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        assertEquals(1, loose.search("另一个话题", 5).size(), "阈值 0.3 → 0.35 分应保留");

        JsonKnowledgeStore strict = new JsonKnowledgeStore(mapper, angled,
                tmp.resolve("strict.json").toString(), 0.5);
        strict.addAll(List.of(item("1", "位移", "位置变化")));
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

        List<KnowledgeItem> hits = store.search("路程", 5);

        assertEquals("路程", hits.get(0).name(), "字面命中的应排前面(关键词那 0.3 是关键)");
    }

    @Test
    @DisplayName("向量模型指纹不匹配:旧向量不加载,关键词检索仍可用")
    void vectorModelMismatchFallsBackToKeyword() {
        store.addAll(List.of(
                item("1", "IoC", "控制反转,把创建对象的权力交给容器"),
                item("2", "线程", "进程里的执行单元")));

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
        assertEquals(2, vectorCount(), "加进来就该顺带算好 2 条向量");

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
