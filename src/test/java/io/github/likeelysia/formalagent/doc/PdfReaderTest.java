package io.github.likeelysia.formalagent.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.likeelysia.formalagent.ocr.OcrEngine;
import io.github.likeelysia.formalagent.ocr.OcrEngines;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfReaderTest {

    @TempDir Path tmp;

    /** 假 OCR(不联网) */
    private final OcrEngine fakeOcr = image -> "OCR 出来的文字";

    private PdfReader reader() {
        return new PdfReader(OcrEngines.single(fakeOcr));
    }

    /** 造一个 2 页、有文字层的 PDF */
    private Path makeTextPdf() throws IOException {
        Path file = tmp.resolve("book.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= 2; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("page " + i + " content");
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** 造一个空白页 PDF(= 没有文字层,模拟扫描件) */
    private Path makeBlankPdf() throws IOException {
        Path file = tmp.resolve("scan.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    @DisplayName("有文字层:按页抽文字,出处标到页码")
    void readsTextLayerPerPage() throws IOException {
        List<TextSegment> segments = reader().read(makeTextPdf());

        assertEquals(2, segments.size());
        assertEquals("第 1 页", segments.get(0).location());
        assertTrue(segments.get(0).text().contains("page 1 content"));
        assertTrue(segments.get(1).text().contains("page 2 content"));
    }

    @Test
    @DisplayName("无文字层(扫描件)→ 走 OCR 兜底")
    void scannedPdfFallsBackToOcr() throws IOException {
        List<TextSegment> segments = reader().read(makeBlankPdf());

        assertEquals(1, segments.size());
        assertEquals("第 1 页", segments.get(0).location());
        assertTrue(segments.get(0).text().contains("OCR 出来的文字"));
    }
}