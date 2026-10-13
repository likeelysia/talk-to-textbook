package io.github.likeelysia.formalagent.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.llm.LocalEmbeddingClient;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 检索评测(golden set)。
 *
 * <p><b>为什么要有它</b>:改一次权重、动一次阈值、换一次 prompt,光靠"随手问两句"根本判断不出
 * 是变好还是变坏 —— 必须有一组<b>人工标好答案</b>的题目,每次改动都拿同一把尺子量一遍。
 *
 * <p><b>样例格式</b>:{@code src/test/resources/retrieval_eval.json},一条 = 一个问题 + 期望命中的
 * 知识点(按 name 认)。{@code expectName} 写 null = 「这题本来就没有答案」,期望检索<b>返回空</b>
 * (考的是不乱答)。
 *
 * <p><b>四个指标</b>:
 * <ul>
 *   <li>{@code recall@5} —— 期望条目出现在前 5 条里的比例(找得到吗);</li>
 *   <li>{@code MRR} —— 命中名次的倒数平均(排得靠前吗,只认第一条命中);</li>
 *   <li>{@code 页命中@5} —— 前 5 条里出现期望页码的比例(出处对不对);</li>
 *   <li>{@code 拒答率} —— 无答案题返回空的比例(不该答的时候闭嘴了吗)。</li>
 * </ul>
 *
 * <p><b>两个测试</b>:
 * <ol>
 *   <li>小样本自检(不依赖外部服务)—— 保证"尺子本身"是准的;</li>
 *   <li>真库评测(需要本机 8090 的 embedding 服务;没开就自动跳过)</li>
 * </ol>
 *
 * <p>门槛值第一次先放宽,拿到真实数字后再收紧 —— <b>阈值要靠实测标定,不能拍脑袋</b>。
 */
class RetrievalEvalTest {

    /**
     * 跟生产环境保持一致:Spring Boot 默认关掉「未知字段就报错」。
     * 踩过的坑:llama.cpp 的 /v1/embeddings 响应里多带一个 index 字段,严格 mapper 会直接抛
     * UnrecognizedPropertyException → 每个 embedding 调用都失败 → 检索被静默降级成纯关键词,
     * 表现就是「换个说法就再也搜不到」(本次评测第一跑 recall 只有 0.06 的真凶)。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 评测样例:expectName 为 null = 这题没有答案,期望检索返回空。 */
    record EvalCase(String query, String expectName, String expectPage) {

        /** 有期望答案吗。注意别叫 isAnswerable() —— 免得被 Jackson 当成 JSON 属性。 */
        boolean answerable() {
            return expectName != null && !expectName.isBlank();
        }
    }

    /** 一份评测报告。 */
    record Metrics(int answerable, int hit, double recall, double mrr,
                   double pageHit, int noAnswerTotal, int refused, double refusal,
                   List<String> recallMisses, List<String> pageMisses) {
    }

    // ---------------------------------------------------------------- 算分(可单测)

    static Metrics evaluate(KnowledgeStore store, List<EvalCase> cases, int k) {
        int answerable = 0;
        int hit = 0;
        int pageHits = 0;
        double rrSum = 0;
        int noAnswerTotal = 0;
        int refused = 0;
        List<String> recallMisses = new ArrayList<>();
        List<String> pageMisses = new ArrayList<>();

        for (EvalCase c : cases) {
            List<KnowledgeItem> hits = store.search(c.query(), k);

            if (!c.answerable()) {                       // 无答案题:返回空 = 正确拒答
                noAnswerTotal++;
                if (hits.isEmpty()) refused++;
                continue;
            }

            answerable++;
            int rank = rankOf(hits, c);
            if (rank > 0) {
                hit++;
                rrSum += 1.0 / rank;
            } else {
                recallMisses.add(c.query() + "  →  该命中「" + c.expectName() + "」");
            }
            if (c.expectPage() != null
                    && hits.stream().anyMatch(i -> c.expectPage().equals(i.location()))) {
                pageHits++;
            } else {
                pageMisses.add(c.query() + "  →  该命中 " + c.expectPage());
            }
        }

        return new Metrics(answerable, hit, ratio(hit, answerable), ratio(rrSum, answerable),
                ratio(pageHits, answerable), noAnswerTotal, refused, ratio(refused, noAnswerTotal),
                List.copyOf(recallMisses), List.copyOf(pageMisses));
    }

    /** 期望条目在返回列表里的名次(从 1 起);没命中 = 0。 */
    private static int rankOf(List<KnowledgeItem> hits, EvalCase c) {
        for (int i = 0; i < hits.size(); i++) {
            if (c.expectName().equals(hits.get(i).name())) return i + 1;
        }
        return 0;
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static String report(String title, Metrics m) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%n===== %s =====%n"
                        + "可回答题 %d | 命中@5 %d | recall@5 %.2f | MRR %.2f | 页命中@5 %.2f%n"
                        + "应拒答题 %d | 正确拒答 %d | 拒答率 %.2f%n",
                title, m.answerable(), m.hit(), m.recall(), m.mrr(), m.pageHit(),
                m.noAnswerTotal(), m.refused(), m.refusal()));
        for (String miss : m.recallMisses()) sb.append("  [漏] ").append(miss).append('\n');
        for (String miss : m.pageMisses()) sb.append("  [页错] ").append(miss).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- ① 尺子自检

    @Test
    @DisplayName("评测算法自检:小样本上 recall / MRR / 页命中 / 拒答率都算得对")
    void metricsHarnessIsCorrectOnTinyFixture(@TempDir Path tmp) {
        // 假 embedder:按意思分组(位移类 → (1,0);线程类 → (0,1))
        EmbeddingClient fake = text -> {
            if (text.matches(".*(位移|挪动|位置差).*")) return new float[]{1f, 0f};
            if (text.contains("线程")) return new float[]{0f, 1f};
            return new float[]{0.2f, 0.2f};   // 不相干话题 → 与两条都只有 ~0.71 余弦
        };
        // 阈值取 0.5:不相干话题的 0.7 × 0.71 ≈ 0.49 会被挡在门外,正好用来验「拒答」这一路
        JsonKnowledgeStore store = new JsonKnowledgeStore(MAPPER, fake,
                tmp.resolve("base.json").toString(), 0.5);
        store.addAll(List.of(
                new KnowledgeItem("1", "位移", "质点从A到B位置矢量的增量", "book.pdf", "第 1 页", "pdf"),
                new KnowledgeItem("2", "线程", "进程里的执行单元", "book.pdf", "第 2 页", "pdf")));

        Metrics m = evaluate(store, List.of(
                new EvalCase("物体挪动前后的位置差", "位移", "第 1 页"),
                new EvalCase("线程是什么", "线程", "第 2 页"),
                new EvalCase("今天天气怎么样", null, null)), 5);

        assertEquals(1.0, m.recall(), 0.0001, "两条都该命中");
        assertEquals(1.0, m.mrr(), 0.0001, "都该排第一");
        assertEquals(1.0, m.pageHit(), 0.0001, "页码都该对上");
        assertEquals(1.0, m.refusal(), 0.0001, "不相干问题该被阈值挡掉");
    }

    // ---------------------------------------------------------------- ② 真库评测

    @Test
    @DisplayName("真库评测:golden set 跑《大学物理学》全库(需要 8090 embedding 服务,没有则跳过)")
    void goldenSetAgainstRealBook(@TempDir Path tmp) throws Exception {
        Path kb = Paths.get("knowledge", "base.json");
        Path sidecar = Paths.get("knowledge", "base.vectors.json");
        assumeTrue(Files.exists(kb), "没找到真库 knowledge/base.json,跳过");
        assumeTrue(Files.exists(sidecar), "没找到向量 sidecar,跳过(免得现场重算 3807 条向量)");

        String url = "http://127.0.0.1:8090";
        assumeTrue(portOpen(url), "embedding 服务没开(" + url + "),跳过;开了再跑这条");

        // 拷到临时目录再评测:检索会回写向量 sidecar,绝不碰真库
        Path kbCopy = tmp.resolve("base.json");
        Files.copy(kb, kbCopy, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sidecar, tmp.resolve("base.vectors.json"), StandardCopyOption.REPLACE_EXISTING);

        AppConfig cfg = new AppConfig(false,
                new AppConfig.Llm(60, 5, 1, 100, 100),
                null, null,
                new AppConfig.Embedding(url, "bge-small-zh-v1.5"),
                null, null, null);
        EmbeddingClient embedder = new LocalEmbeddingClient(cfg, MAPPER);
        // 阈值用生产值 0.35,量的才是「线上那套」的真实水平
        KnowledgeStore store = new JsonKnowledgeStore(MAPPER, embedder, kbCopy.toString(), 0.35);

        Metrics m = evaluate(store, loadCases(), 5);
        System.out.print(report("retrieval eval · 大学物理学(第四版)", m));

        // 门槛 = 2026-10-10 首次实测值(recall .86 / MRR .77 / 页命中 .89 / 拒答 1.00)往下留出的回归线。
        // 换 embedding 模型、重灌知识库、动检索权重之后,记得重新标定这几个数。
        assertTrue(m.recall() >= 0.80, "recall@5 太低:" + m.recall());
        assertTrue(m.mrr() >= 0.70, "MRR 太低:" + m.mrr());
        assertTrue(m.pageHit() >= 0.80, "页命中@5 太低:" + m.pageHit());
        assertTrue(m.refusal() >= 0.80, "拒答率太低:" + m.refusal());
    }

    // ---------------------------------------------------------------- 小工具

    private static List<EvalCase> loadCases() throws Exception {
        try (InputStream in = RetrievalEvalTest.class.getResourceAsStream("/retrieval_eval.json")) {
            assertTrue(in != null, "评测样例 retrieval_eval.json 不在测试资源里");
            return MAPPER.readValue(in, new TypeReference<List<EvalCase>>() {});
        }
    }

    /** 端口通不通(连一下就行,不真调接口)。 */
    private static boolean portOpen(String url) {
        URI uri = URI.create(url);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
