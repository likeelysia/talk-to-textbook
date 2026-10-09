package io.github.likeelysia.formalagent.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.doc.DocPipeline;
import io.github.likeelysia.formalagent.doc.ImageReader;
import io.github.likeelysia.formalagent.doc.PdfReader;
import io.github.likeelysia.formalagent.doc.PlainTextReader;
import io.github.likeelysia.formalagent.doc.ReaderRegistry;
import io.github.likeelysia.formalagent.doc.TextChunk;
import io.github.likeelysia.formalagent.doc.TextSplitter;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.knowledge.JsonKnowledgeStore;
import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.ocr.OcrEngines;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 断点灌库({@link CheckpointIngestService})的行为测试。
 *
 * <p>用一个"假的 DocPipeline"顶替真 PDF/OCR:页数我定、每一块我记下来读了哪一段,
 * 这样"续灌到底有没有回头重读"才有硬证据(靠日志看不出)。
 */
class CheckpointIngestServiceTest {

    @TempDir Path tmp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> loadedRanges = new ArrayList<>();   // 记录 pipeline 真正读了哪些页段
    private final Set<Integer> failOnce = new LinkedHashSet<>();   // 这些页"第一遍读会失败"(模拟 429)
    private final Set<Integer> blankPages = new LinkedHashSet<>();  // 这些页"读出来是空的"(模拟空白页/篇扉页)
    private int totalPages = 406;
    private boolean boomAt81 = false;                             // 模拟"第 3 块(81-120 页)断网"
    private Integer alwaysFailPage = null;                        // 某一页永远读不出来(模拟扫描太糊)

    private JsonKnowledgeStore store;
    private DocPipeline pipeline;
    private CheckpointIngestService service;
    private Path book;
    private Path ckDir;

    @BeforeEach
    void setUp() throws Exception {
        loadedRanges.clear();
        failOnce.clear();
        blankPages.clear();
        totalPages = 406;
        boomAt81 = false;
        alwaysFailPage = null;

        book = tmp.resolve("book.pdf");
        Files.writeString(book, "whatever", StandardCharsets.UTF_8);   // 内容无关紧要,读取器是假的

        pipeline = new DocPipeline(
                new ReaderRegistry(new PlainTextReader(),
                        new PdfReader(OcrEngines.single(image -> "")),
                        new ImageReader((image, prompt) -> "")),
                new TextSplitter(20, 5)) {
            @Override
            public int pageCount(Path file) {
                return totalPages;
            }

            @Override
            public DocPipeline.LoadedDoc loadReport(Path file, int fromPage, int toPage) {
                loadedRanges.add(fromPage + "-" + toPage);
                if (boomAt81 && fromPage == 81) throw new AgentException("模拟:第 81 页起断网");
                List<TextChunk> chunks = new ArrayList<>();
                List<Integer> failed = new ArrayList<>();
                for (int p = fromPage; p <= toPage; p++) {
                    if (blankPages.remove(p)) {
                        continue;                                 // 这页"读出来是空的":既没文本,也不是失败
                    }
                    if (failOnce.remove(p) || (alwaysFailPage != null && alwaysFailPage == p)) {
                        failed.add(p);                                // 这一页"OCR 失败":没文本,只有页号
                        continue;
                    }
                    chunks.add(new TextChunk("t" + p, "第 " + p + " 页"));   // 一页一块,名字带上页码
                }
                return new DocPipeline.LoadedDoc(chunks, failed);
            }
        };

        // 假模型:把拿到的文本原样回一个知识点 → 名字 = K-t<页码>,方便数条数
        LlmClient fake = (history, options) -> "[{\"name\":\"K-"
                + history.get(history.size() - 1).getContent() + "\",\"detail\":\"d\"}]";

        store = new JsonKnowledgeStore(mapper, text -> new float[]{1f, 0f},
                tmp.resolve("base.json").toString(), 0);
        ckDir = tmp.resolve("checkpoints");
        service = new CheckpointIngestService(pipeline, new KnowledgeExtractor(fake, mapper),
                store, mapper, ckDir.toString(), 40);
    }

    @Test
    @DisplayName("每 40 页一个断点:406 页切 11 块,块块落库,最后一块不足 40")
    void blocksEvery40Pages() {
        IngestProgress p = service.ingest(book);

        assertEquals(40, service.blockSize(), "断点粒度 = 40 页");
        assertEquals(406, p.totalPages());
        assertEquals(406, p.donePages());
        assertEquals(406, p.itemCount(), "一页一条 → 406 条");
        assertEquals(406, store.all().size(), "知识库应真落下了 406 条");
        assertEquals(List.of("1-40", "41-80", "81-120", "121-160", "161-200", "201-240",
                        "241-280", "281-320", "321-360", "361-400", "401-406"),
                loadedRanges, "块边界:每 40 页一刀,最后一块只到 406");
        assertEquals(1, service.allProgress().size(), "应留下一个断点记录");
    }

    @Test
    @DisplayName("第 3 块挂掉:断点停在 80 页,重跑从 81 页续,绝不回头重灌前两块")
    void resumesAfterCrash() {
        boomAt81 = true;
        assertThrows(AgentException.class, () -> service.ingest(book));

        assertEquals(List.of("1-40", "41-80", "81-120"), loadedRanges, "挂掉的那块读过但没算数");
        assertEquals(80, store.all().size(), "前两块(80 页)已经落库了");
        assertEquals(80, service.allProgress().get(0).donePages(), "断点 = 80 页");

        loadedRanges.clear();
        boomAt81 = false;
        IngestProgress p = service.ingest(book);

        assertEquals(406, p.donePages());
        assertEquals("81-120", loadedRanges.get(0), "重跑从挂掉的那一块开始");
        assertEquals(9, loadedRanges.size(), "剩下 9 块(第 3 块重做 + 第 4~11 块)");
        assertEquals(406, store.all().size(), "最终一条不多一条不少");
    }

    @Test
    @DisplayName("已经灌完的书再跑:直接跳过,一页都不读")
    void skipsWhenFinished() {
        service.ingest(book);
        loadedRanges.clear();

        IngestProgress p = service.ingest(book);

        assertEquals(406, p.donePages());
        assertTrue(loadedRanges.isEmpty(), "已完成的书不该再读任何一页");
        assertEquals(406, store.all().size(), "也不会重复入库");
    }

    @Test
    @DisplayName("重灌:只在从头开始时清旧条目,续灌时不能把已灌好的块误清")
    void replaceClearsOldOnlyAtStart() {
        String src = book.getFileName().toString();
        store.addAll(List.of(new KnowledgeItem(src + "|OLD", "OLD", "旧版残留", src, "第 1 页", "pdf")));

        boomAt81 = true;
        assertThrows(AgentException.class, () -> service.ingest(book, null, true, false));

        assertTrue(store.all().stream().noneMatch(k -> "OLD".equals(k.name())), "旧条目该被清掉");
        assertEquals(80, store.all().size(), "续灌时不能顺手把已灌好的 80 条也清了");

        boomAt81 = false;
        service.ingest(book, null, true, false);        // 再带 replace 重跑 —— 因为是续灌,不重清
        assertEquals(406, store.all().size());
    }

    @Test
    @DisplayName("整块全失败:抛错,断点停在此块之前(不吞进度、不假装成功)")
    void wholeBlockFailureDoesNotAdvance() {
        LlmClient alwaysFail = (history, options) -> {
            throw new AgentException("模型挂了");
        };
        CheckpointIngestService failing = new CheckpointIngestService(
                pipeline, new KnowledgeExtractor(alwaysFail, mapper), store, mapper, ckDir.toString(), 40);

        assertThrows(AgentException.class, () -> failing.ingest(book));

        assertEquals(0, failing.allProgress().get(0).donePages(), "第一块没成功 → 进度必须是 0");
        assertEquals(0, store.all().size(), "不能凭空写入空条目");
    }

    @Test
    @DisplayName("整块都是空白页(读完了但没正文)→ 不算失败,进度照常往前,漏页清单干干净净")
    void blankBlockIsNotAFailure() {
        totalPages = 40;
        for (int p = 1; p <= 40; p++) blankPages.add(p);

        IngestProgress p = service.ingest(book);

        assertEquals(40, p.donePages(), "空白页不阻塞进度");
        assertTrue(p.failedPages().isEmpty(), "空白不是失败,不进漏页清单");
        assertEquals(0, store.all().size(), "不值得入库的东西一条也不写");
    }

    @Test
    @DisplayName("OCR 漏页:失败页号记进断点;下次开跑只补灌那一页,补上就划掉")
    void recordsAndRetriesFailedPage() {
        failOnce.add(7);                                          // 第 7 页第一遍读失败(429)

        IngestProgress p1 = service.ingest(book);

        assertEquals(List.of(7), p1.failedPages(), "第 7 页没读成功 → 记进漏页清单");
        assertEquals(405, store.all().size(), "第 7 页没进库");
        assertEquals(406, p1.donePages(), "但整本还是往前走完了(一页不拖死一整本)");
        assertEquals(0, p1.failedChunks(), "这是 OCR 漏页,不是提炼失败");

        loadedRanges.clear();
        IngestProgress p2 = service.ingest(book);

        assertEquals(List.of("7-7"), loadedRanges, "第二遍只补灌第 7 页,其它页一个都不重读");
        assertTrue(p2.failedPages().isEmpty(), "补上后清单清空");
        assertEquals(406, store.all().size(), "一页不多一页不少");
    }

    @Test
    @DisplayName("漏页补灌又失败:留在清单里,不阻塞、不死循环,下次开跑再试")
    void keepsRetryingFailedPageWithoutBlocking() {
        alwaysFailPage = 7;                                       // 这一页永远读不出来

        IngestProgress p1 = service.ingest(book);
        assertEquals(List.of(7), p1.failedPages());
        assertEquals(405, store.all().size());

        loadedRanges.clear();
        IngestProgress p2 = service.ingest(book);                 // 重跑 → 先补灌 7 → 又失败

        assertEquals(List.of("7-7"), loadedRanges, "只补灌那一页,不重读整本");
        assertEquals(List.of(7), p2.failedPages(), "还是没成 → 继续留着");
        assertEquals(405, store.all().size(), "也不会重复入库");
    }

    @Test
    @DisplayName("提炼失败的块:页号也进漏页清单,同样能补灌")
    void failedChunkPageIsTrackedToo() {
        LlmClient flaky = (history, options) -> {
            String text = history.get(history.size() - 1).getContent();
            if ("t5".equals(text)) throw new AgentException("模拟:模型打嗝");
            return "[{\"name\":\"K-" + text + "\",\"detail\":\"d\"}]";
        };
        CheckpointIngestService svc = new CheckpointIngestService(
                pipeline, new KnowledgeExtractor(flaky, mapper), store, mapper, ckDir.toString(), 40);

        IngestProgress p = svc.ingest(book);

        assertEquals(List.of(5), p.failedPages(), "第 5 页所在的块提炼失败 → 页号也记上");
        assertEquals(1, p.failedChunks(), "块级失败计数保留");
        assertEquals(405, store.all().size());
    }

    @Test
    @DisplayName("旧断点文件没有 failedPages 字段:反序列化不炸,当空表(当作干净完成)")
    void oldCheckpointWithoutFailedPagesIsFine() throws Exception {
        String old = "{\"source\":\"旧书.pdf\",\"fileName\":\"旧书.pdf\",\"fileSize\":1,"
                + "\"totalPages\":10,\"blockSize\":40,\"donePages\":10,\"itemCount\":3,"
                + "\"failedChunks\":0,\"updatedAt\":\"2026-10-07 20:00:00\"}";

        IngestProgress p = mapper.readValue(old, IngestProgress.class);

        assertTrue(p.failedPages().isEmpty(), "缺失字段 → 空表,不是 null");
        assertTrue(p.completedCleanly(), "旧断点当作'干净完成',不会平白重灌");
    }

    @Test
    @DisplayName("源文件换版本(页数变了):旧断点作废,老老实实重灌")
    void invalidatesOnVersionChange() {
        service.ingest(book);
        loadedRanges.clear();

        totalPages = 410;                                // 同一路径、页数变了
        service.ingest(book);

        assertEquals("1-40", loadedRanges.get(0), "页数变了 → 不信旧断点");
        assertEquals(410, store.all().size());
    }
}
