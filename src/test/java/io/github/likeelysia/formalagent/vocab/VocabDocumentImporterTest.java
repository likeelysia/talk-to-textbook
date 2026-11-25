package io.github.likeelysia.formalagent.vocab;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

class VocabDocumentImporterTest {
    private final VocabDocumentImporter importer = new VocabDocumentImporter();
    private String read(String name, byte[] bytes) {
        return importer.read(new MockMultipartFile("file", name, "application/octet-stream", bytes)).text();
    }
    @Test void textEncodingsAndTextFormats() {
        assertThat(read("notes.MD", "The river bank.\n中文".getBytes(StandardCharsets.UTF_8))).contains("river", "中文");
        assertThat(read("words.csv", "word,中文\nbank,银行".getBytes(java.nio.charset.Charset.forName("GB18030")))).contains("银行");
        assertThat(read("words.tsv", "bank\t银行".getBytes(StandardCharsets.UTF_16))).contains("银行");
        assertThat(read("page.html", ("<html><head><style>secretStyle</style><script>secretScript</script></head>"
                + "<body><p>The river &amp; bank.</p><p>Next line.</p></body></html>").getBytes(StandardCharsets.UTF_8)))
                .contains("river & bank.", "Next line.").doesNotContain("secretScript", "secretStyle", "<p>");
        assertThat(read("sample.rtf", "{\\rtf1\\ansi The river bank.\\par Next line.}".getBytes(StandardCharsets.US_ASCII)))
                .contains("river bank", "Next line").doesNotContain("rtf1");
    }
    @Test void wordParagraphsAndTablesRetainText() throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("The river bank.");
            doc.createTable(1,1).getRow(0).getCell(0).setText("A table sentence.");
            doc.write(out);
            assertThat(read("essay.docx", out.toByteArray())).contains("river bank", "table sentence");
        }
    }
    @Test void slidesIncludeTextAndTables() throws Exception {
        try (var doc = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            var slide = doc.createSlide();
            slide.createTextBox().setText("The river bank.");
            slide.createTable(1,1).getCell(0,0).setText("Table vocabulary.");
            doc.write(out);
            assertThat(read("slides.pptx", out.toByteArray())).contains("river bank", "Table vocabulary");
        }
    }
    @Test void spreadsheetsIncludeCellsFromDifferentSheetsAndOldXls() throws Exception {
        try (var doc = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            doc.createSheet("one").createRow(0).createCell(0).setCellValue("The river bank.");
            doc.createSheet("two").createRow(0).createCell(0).setCellValue("Second sentence.");
            doc.write(out);
            assertThat(read("words.xlsx", out.toByteArray())).contains("river bank", "Second sentence");
        }
        try (var doc = new HSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            doc.createSheet().createRow(0).createCell(0).setCellValue("Old Excel vocabulary.");
            doc.write(out);
            assertThat(read("words.xls", out.toByteArray())).contains("Excel vocabulary");
        }
    }
    @Test void excelBatchesReadOnlyNonEmptyRowsWithProgress() throws Exception {
        try (var doc = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = doc.createSheet("words");
            for (int i = 0; i < 5; i++) sheet.createRow(i * 2).createCell(0).setCellValue("word" + i);
            doc.write(out);
            var file = new MockMultipartFile("file", "words.xlsx", "application/octet-stream", out.toByteArray());
            var first = importer.readSheetBatch(file, 0, 2);
            assertThat(first.totalRows()).isEqualTo(5);
            assertThat(first.text()).isEqualTo("word0\nword1");
            assertThat(first.nextOffset()).isEqualTo(2);
            assertThat(first.done()).isFalse();
            var last = importer.readSheetBatch(file, 4, 2);
            assertThat(last.text()).isEqualTo("word4");
            assertThat(last.done()).isTrue();
            assertThat(importer.readSheetBatch(file, 9, 2).done()).isTrue();
        }
    }
    @Test void sparseSheetsDoNotTurnEmptyCellsIntoText() throws Exception {
        try (var doc = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = doc.createSheet("sparse");
            sheet.createRow(0).createCell(0).setCellValue("bank");
            sheet.createRow(1); // 空行
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("river");
            row.createCell(4).setCellValue("water");
            doc.write(out);
            assertThat(read("sparse.xlsx", out.toByteArray())).isEqualTo("bank\nriver\twater");
        }
    }
    @Test void pdfReadsTextAndRejectsImageOnlyAndTooManyPages() throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(40,700); content.showText("The river bank."); content.endText();
            }
            doc.save(out);
            assertThat(read("reading.pdf", out.toByteArray())).contains("river bank");
        }
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); doc.save(out);
            assertThatThrownBy(() -> read("scan.pdf", out.toByteArray())).hasMessageContaining("OCR");
        }
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i=0;i<151;i++) doc.addPage(new PDPage());
            doc.save(out);
            assertThatThrownBy(() -> read("book.pdf", out.toByteArray())).hasMessageContaining("150");
        }
    }
    @Test void rejectsUnsupportedCorruptEmptyAndOversizedFilesWithoutPoisoningNextImport() throws Exception {
        assertThatThrownBy(() -> read("old.doc", new byte[]{1})).hasMessageContaining("DOCX");
        assertThatThrownBy(() -> read("broken.docx", "not a zip".getBytes())).hasMessageContaining("格式");
        assertThatThrownBy(() -> read("broken.pdf", new byte[]{1})).hasMessageContaining("无法读取");
        assertThatThrownBy(() -> read("empty.txt", new byte[0])).hasMessageContaining("非空");
        assertThatThrownBy(() -> read("binary.txt", new byte[]{0,1,2})).hasMessageContaining("纯文本");
        assertThatThrownBy(() -> read("big.txt", new byte[VocabDocumentImporter.MAX_FILE_BYTES+1])).hasMessageContaining("8 MB");
        assertThatThrownBy(() -> read("long.txt", "a".repeat(200001).getBytes())).hasMessageContaining("200000");
        try (var out = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("large.xml"));
                byte[] block = new byte[1024*1024];
                for (int i=0;i<33;i++) zip.write(block);
                zip.closeEntry();
            }
            assertThatThrownBy(() -> read("bomb.docx", out.toByteArray())).hasMessageContaining("解压后过大");
        }
        assertThat(read("normal.txt", "Normal document.".getBytes())).isEqualTo("Normal document.");
    }
}
