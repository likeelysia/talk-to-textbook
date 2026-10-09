package io.github.likeelysia.formalagent.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.doc.DocPipeline;
import io.github.likeelysia.formalagent.doc.PlainTextReader;
import io.github.likeelysia.formalagent.doc.TextSplitter;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.ocr.OcrEngines;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.likeelysia.formalagent.llm.ChatOptions;
import io.github.likeelysia.formalagent.doc.PdfReader;
import io.github.likeelysia.formalagent.doc.ReaderRegistry;
import io.github.likeelysia.formalagent.doc.ImageReader;

/**
 * ExtractService 的单元测试。
 * 管道 + 提取器都是"真家伙",只有最外层的大模型换成桩 → 依然全程不联网。
 */
class ExtractServiceTest {

    /** 三段(每段 12 字),用 chunkSize=20 / overlap=5 → 会切成 3 块 */
    private static final String TEXT = "aaaaaaaaaaaa\n\nbbbbbbbbbbbb\n\ncccccccccccc";

    /** 桩:按顺序吐答案,并记下被调用了几次 */
    private static class QueuedFake implements LlmClient {
        private final Queue<String> answers;
        private int calls;

        QueuedFake(String... answers) { this.answers = new ArrayDeque<>(List.of(answers)); }

        @Override public String chat(List<Message> history, ChatOptions options) {
            calls++;
            String answer = answers.poll();
            if (answer == null) throw new IllegalStateException("桩没准备好第 " + calls + " 次答案");
            return answer;
        }
    }

    private static String json(String name, String detail) {
        return "[{\"name\":\"" + name + "\",\"detail\":\"" + detail + "\"}]";
    }

    /** 手动装配提取器:桩客户端 + 普通 ObjectMapper(测试不起 Spring 容器) */
    private static KnowledgeExtractor extractor(LlmClient client) {
        return new KnowledgeExtractor(client, new ObjectMapper());
    }

    @TempDir Path tmp;
    private Path file;
    private DocPipeline pipeline;

    @BeforeEach
    @DisplayName("每条测试前:写一个 3 段的临时文件 + 造好管道")
    void setUp() throws Exception {
        file = tmp.resolve("lesson.md");
        Files.writeString(file, TEXT, StandardCharsets.UTF_8);
        pipeline = new DocPipeline(
                new ReaderRegistry(new PlainTextReader(), new PdfReader(OcrEngines.single(image -> "")),
                        new ImageReader((image, prompt) -> "")),
                new TextSplitter(20, 5));
    }

    @Test
    @DisplayName("3 块 → 汇总成 3 个知识点,顺序和内容都对")
    void mergesAllChunks() {
        QueuedFake fake = new QueuedFake(json("K1", "d1"), json("K2", "d2"), json("K3", "d3"));

        List<KnowledgePoint> points = new ExtractService(pipeline, extractor(fake)).extractFile(file);

        assertEquals(3, points.size());
        assertEquals("K1", points.get(0).name());
        assertEquals("d3", points.get(2).detail());
        assertEquals(3, fake.calls);
    }

    @Test
    @DisplayName("两块出现同名知识点 → 只留一条,说明用第一次的")
    void dedupesByName() {
        QueuedFake fake = new QueuedFake(json("X", "第一次"), json("X", "第二次"), "[]");

        List<KnowledgePoint> points = new ExtractService(pipeline, extractor(fake)).extractFile(file);

        assertEquals(1, points.size());
        assertEquals("X", points.get(0).name());
        assertEquals("第一次", points.get(0).detail());
    }

    @Test
    @DisplayName("模型全程没提取到东西 → 空列表")
    void emptyWhenNothingFound() {
        QueuedFake fake = new QueuedFake("[]", "[]", "[]");

        List<KnowledgePoint> points = new ExtractService(pipeline, extractor(fake)).extractFile(file);

        assertTrue(points.isEmpty());
        assertEquals(3, fake.calls);   // 三块都问了,只是都没东西
    }

    @Test
    @DisplayName("某一块模型乱答 → 直接抛 AgentException(不吞异常)")
    void failsFastOnBadChunk() {
        QueuedFake fake = new QueuedFake("[]", "抱歉我做不到", "[]");

        assertThrows(AgentException.class,
                () -> new ExtractService(pipeline, extractor(fake)).extractFile(file));
    }
}
