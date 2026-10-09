package io.github.likeelysia.formalagent.doc;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.VisionClient;
import io.github.likeelysia.formalagent.ocr.OcrEngines;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReaderRegistryTest {

    private final VisionClient noop = (image, prompt) -> "";
    private final ReaderRegistry registry =
            new ReaderRegistry(new PlainTextReader(), new PdfReader(OcrEngines.single(image -> "")), new ImageReader(noop));

    @Test
    @DisplayName("按扩展名分派:txt/md→文本,pdf→PDF,png/jpg→图片")
    void dispatchesByExtension() {
        assertInstanceOf(PlainTextReader.class, registry.forFile(Path.of("a.md")));
        assertInstanceOf(PlainTextReader.class, registry.forFile(Path.of("a.txt")));
        assertInstanceOf(PdfReader.class, registry.forFile(Path.of("a.pdf")));
        assertInstanceOf(ImageReader.class, registry.forFile(Path.of("a.png")));
        assertInstanceOf(ImageReader.class, registry.forFile(Path.of("a.JPG")));
    }

    @Test
    @DisplayName("不支持的格式 → 抛 AgentException")
    void unsupportedThrows() {
        assertThrows(AgentException.class, () -> registry.forFile(Path.of("a.docx")));
    }
}