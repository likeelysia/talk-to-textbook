package io.github.likeelysia.formalagent.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.ocr.OcrEngine;
import io.github.likeelysia.formalagent.ocr.OcrEngines;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PdfReader} 的「按页读」测试 —— 灌库断点全靠它:页码必须准(它就是出处),
 * 越界必须兜住(最后一块常常不足 40 页)。
 */
class PdfReaderRangeTest {

    @TempDir Path tmp;

    /** 假引擎:一被调用就报错 —— 用来验证「有文字层就不该走 OCR」 */
    private static final OcrEngine NEVER = new OcrEngine() {
        @Override public String name() { return "never"; }
        @Override public String ocr(byte[] image) { throw new AgentException("有文字层就不该走 OCR"); }
    };

    /** 假引擎:固定返回 "ocr-text" */
    private static final OcrEngine CONST = new OcrEngine() {
        @Override public String name() { return "fake"; }
        @Override public String ocr(byte[] image) { return "ocr-text"; }
    };

    private static PdfReader reader(OcrEngine engine) {
        return new PdfReader(OcrEngines.single(engine));
    }

    /** 造一个 pages 页、每页有文字层("page N")的 PDF。 */
    private Path textPdf(int pages) throws Exception {
        Path f = tmp.resolve("text-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 1; p <= pages; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("page " + p);
                    cs.endText();
                }
            }
            doc.save(f.toFile());
        }
        return f;
    }

    /** 造一个 pages 页、没有文字层的 PDF(模拟扫描件)。 */
    private Path blankPdf(int pages) throws Exception {
        Path f = tmp.resolve("blank-" + pages + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < pages; p++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(f.toFile());
        }
        return f;
    }

    @Test
    @DisplayName("pageCount:数得对(断点切几块由它决定)")
    void countsPages() throws Exception {
        assertEquals(5, reader(CONST).pageCount(textPdf(5)));
    }

    @Test
    @DisplayName("不传范围 = 整本读,页码从 1 开始连续")
    void readsWholeDocument() throws Exception {
        List<TextSegment> segments = reader(NEVER).read(textPdf(5));

        assertEquals(5, segments.size());
        assertEquals(List.of("第 1 页", "第 2 页", "第 3 页", "第 4 页", "第 5 页"),
                segments.stream().map(TextSegment::location).toList());
    }

    @Test
    @DisplayName("只读 3~4 页:只回这两页,页码还是全局的(不是 1、2)")
    void readsOnlyRequestedRange() throws Exception {
        List<TextSegment> segments = reader(CONST).read(textPdf(5), 3, 4);

        assertEquals(2, segments.size());
        assertEquals("第 3 页", segments.get(0).location());
        assertEquals("第 4 页", segments.get(1).location());
        assertTrue(segments.get(0).text().contains("page 3"));
        assertTrue(segments.get(1).text().contains("page 4"));
    }

    @Test
    @DisplayName("末页越界:要 4~99 页 → 只给 4、5 页(最后一块常常不满)")
    void clampsAtLastPage() throws Exception {
        List<TextSegment> segments = reader(CONST).read(textPdf(5), 4, 99);

        assertEquals(2, segments.size());
        assertEquals("第 4 页", segments.get(0).location());
        assertEquals("第 5 页", segments.get(1).location());
    }

    @Test
    @DisplayName("范围整段越界:返回空,不抛异常")
    void emptyWhenRangeOutside() throws Exception {
        assertTrue(reader(CONST).read(textPdf(5), 7, 9).isEmpty());
    }

    @Test
    @DisplayName("扫描件走 OCR:只 OCR 请求的那两页,页码照样从 2 起")
    void ocrOnlyRequestedRange() throws Exception {
        List<TextSegment> segments = reader(CONST).read(blankPdf(5), 2, 3);

        assertEquals(2, segments.size());
        assertEquals("第 2 页", segments.get(0).location());
        assertEquals("第 3 页", segments.get(1).location());
    }

    @Test
    @DisplayName("非 PDF 读取器没有'页'概念:pageCount 兜底为 1")
    void textReaderHasSinglePage() throws Exception {
        Path md = tmp.resolve("a.md");
        java.nio.file.Files.writeString(md, "hello");
        assertEquals(1, new PlainTextReader().pageCount(md));
    }
}
