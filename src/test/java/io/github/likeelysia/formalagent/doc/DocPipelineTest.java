package io.github.likeelysia.formalagent.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.likeelysia.formalagent.doc.ReaderRegistry;
import io.github.likeelysia.formalagent.ocr.OcrEngines;

class DocPipelineTest {

    private final DocPipeline pipeline = new DocPipeline(
            new ReaderRegistry(new PlainTextReader(), new PdfReader(OcrEngines.single(image -> "")),
                    new ImageReader((image, prompt) -> "")),
            new TextSplitter(20, 5));

    @TempDir
    Path tmp;

    @Test
    @DisplayName("读文件 + 切块:三段 md 被切成三块")
    void loadAndSplit() throws IOException {
        Path f = tmp.resolve("note.md");
        Files.writeString(f, "aaaaaaaaaaaa\n\nbbbbbbbbbbbb\n\ncccccccccccc", StandardCharsets.UTF_8);

        List<TextChunk> chunks = pipeline.load(f);

        assertEquals(3, chunks.size());
        assertEquals("aaaaaaaaaaaa", chunks.get(0).text());
        assertEquals("aaaaa\nbbbbbbbbbbbb", chunks.get(1).text());   // 开头 5 个 a = 重叠
    }

    @Test
    @DisplayName("文件不存在 → reader 的异常一路冒到调用方(管道不吞)")
    void missingFile() {
        Path f = tmp.resolve("nope.md");
        assertThrows(AgentException.class, () -> pipeline.load(f));
    }

    @Test
    @DisplayName("空白文件 → 照样抛异常(空文档没有知识点可提取)")
    void blankFile() throws IOException {
        Path f = tmp.resolve("blank.md");
        Files.writeString(f, "   \n \n ", StandardCharsets.UTF_8);
        assertThrows(AgentException.class, () -> pipeline.load(f));
    }
}