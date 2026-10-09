package io.github.likeelysia.formalagent.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.doc.DocPipeline;
import io.github.likeelysia.formalagent.doc.PlainTextReader;
import io.github.likeelysia.formalagent.doc.TextSplitter;
import io.github.likeelysia.formalagent.knowledge.JsonKnowledgeStore;
import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.ocr.OcrEngines;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.likeelysia.formalagent.doc.PdfReader;
import io.github.likeelysia.formalagent.doc.ReaderRegistry;
import io.github.likeelysia.formalagent.doc.ImageReader;

class IngestServiceTest {

    @TempDir Path tmp;
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonKnowledgeStore store;
    private DocPipeline pipeline;
    private IngestService service;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        file = tmp.resolve("book.md");
        Files.writeString(file, "aaaa\n\nbbbb\n\ncccc", StandardCharsets.UTF_8);

        pipeline = new DocPipeline(
                new ReaderRegistry(new PlainTextReader(), new PdfReader(OcrEngines.single(image -> "")),
                        new ImageReader((image, prompt) -> "")),
                new TextSplitter(20, 5));
        LlmClient fake = (history, options) -> "[{\"name\":\"K1\",\"detail\":\"d1\"}]";
        store = newStore();
        service = new IngestService(pipeline, new KnowledgeExtractor(fake, mapper), store);
    }

    private JsonKnowledgeStore newStore() {
        return new JsonKnowledgeStore(mapper, text -> new float[]{1f, 0f},
                tmp.resolve("base.json").toString(), 0);
    }

    @Test
    @DisplayName("教材 → 知识点入库:同名去重,记录出处")
    void ingestsIntoStore() {
        List<KnowledgeItem> items = service.ingest(file);

        assertEquals(1, items.size(), "三块都返回 K1 → 去重后 1 条");
        assertEquals("book.md", items.get(0).source(), "应记录来源文件");
        assertEquals(1, store.all().size(), "知识库里应有 1 条");
    }

    @Test
    @DisplayName("重灌:教材改版后,旧版有、新版没有的知识点不会残留")
    void replaceDropsStaleItems() {
        // 第一版:K1 + K2
        AtomicBoolean v1 = new AtomicBoolean(true);
        LlmClient versioned = (history, options) -> v1.get()
                ? "[{\"name\":\"K1\",\"detail\":\"d\"},{\"name\":\"K2\",\"detail\":\"d\"}]"
                : "[{\"name\":\"K1\",\"detail\":\"d\"}]";
        IngestService svc = new IngestService(pipeline, new KnowledgeExtractor(versioned, mapper), store);

        svc.ingest(file);
        assertEquals(2, store.all().size(), "第一版收录 K1、K2");

        // 第二版:教材里删掉了 K2。先看不指定重灌的结果 —— 旧条目按设计保留(所以会新旧混杂)
        v1.set(false);
        svc.ingest(file);
        assertEquals(2, store.all().size(), "普通再灌一次:新条目覆盖同名,旧条目留着");

        // 指定重灌 → 这本书整份换掉
        svc.ingest(file, true);
        assertEquals(1, store.all().size(), "重灌后 K2 应被清掉");
        assertEquals("K1", store.all().get(0).name());
    }

    @Test
    @DisplayName("来源标识可以不是落盘文件名(上传接口得传原始文件名)")
    void sourceNameCanDifferFromFileName() {
        List<KnowledgeItem> items = service.ingest(file, "大学物理.pdf", true);

        assertEquals("大学物理.pdf", items.get(0).source(), "按传入的来源记账");
        assertEquals(List.of("大学物理.pdf"), store.sources());

        service.ingest(file, "   ", false);          // 空白来源 → 回退成文件名,不能把来源写空
        assertEquals(List.of("大学物理.pdf", "book.md"), store.sources());
    }
}
