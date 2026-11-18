package io.github.likeelysia.formalagent.vocab;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipInputStream;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import javax.swing.text.rtf.RTFEditorKit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 只把文档转成文字；不保存原文件、不查词、不写生词本。 */
@Service
public class VocabDocumentImporter {
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ZIP_BYTES = 32 * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of(
            "pdf", "docx", "pptx", "xlsx", "xls", "txt", "md", "csv", "tsv", "html", "htm", "rtf");
    // 小服务器一次解析一份文件，避免多份 Office/PDF 同时占用内存。
    private final Semaphore parserSlot = new Semaphore(1);

    public record ImportedDocument(String filename, String text) { }

    public ImportedDocument read(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new AgentException("请选择非空文档");
        if (file.getSize() > MAX_FILE_BYTES) throw new AgentException("文档不能超过 8 MB，请拆分后导入");
        String name = file.getOriginalFilename();
        name = name == null ? "document" : name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(extension)) {
            throw new AgentException("暂不支持这种格式。旧版 DOC/PPT 请另存为 DOCX/PPTX；图片请先识别为文字");
        }
        if (!parserSlot.tryAcquire()) throw new AgentException("另一个文档正在解析，请稍后再试");
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new AgentException("文档不能超过 8 MB，请拆分后导入");
            if (Set.of("docx", "pptx", "xlsx").contains(extension)) checkArchive(bytes);
            Text text = new Text();
            switch (extension) {
                case "pdf" -> readPdf(bytes, text);
                case "docx" -> readWord(bytes, text);
                case "pptx" -> readSlides(bytes, text);
                case "xlsx", "xls" -> readSheet(bytes, extension, text);
                case "rtf" -> {
                    var document = new DefaultStyledDocument();
                    new RTFEditorKit().read(new ByteArrayInputStream(bytes), document, 0);
                    text.add(document.getText(0, document.getLength()));
                }
                case "html", "htm" -> readHtml(decode(bytes), text);
                default -> text.add(decode(bytes));
            }
            String result = text.toString().replace("\u0000", "").trim();
            if (result.isBlank()) throw new AgentException("没有读到文字。扫描版 PDF 或图片请先进行 OCR 文字识别");
            return new ImportedDocument(name, result);
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            // 不把解析器堆栈、路径或文档内部内容返回给浏览器。
            throw new AgentException("文档无法读取，可能已损坏、加密或与扩展名不符。请另存为正常文档再试");
        } finally {
            parserSlot.release();
        }
    }

    private static void readPdf(byte[] bytes, Text text) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent())
                throw new AgentException("请先解除 PDF 加密及文字提取限制");
            if (document.getNumberOfPages() > 150) throw new AgentException("PDF 最多 150 页，请拆分后导入");
            // writeText 边读取边检查字数，超限立即停止，不先生成整本书的字符串。
            new PDFTextStripper().writeText(document, text);
        }
    }

    private static void readWord(byte[] bytes, Text text) throws IOException {
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            readWordBody(document, text);
        }
    }

    private static void readWordBody(IBody body, Text text) {
        for (IBodyElement element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) text.line(paragraph.getText());
            if (element instanceof XWPFTable table) {
                for (var row : table.getRows()) for (var cell : row.getTableCells()) readWordBody(cell, text);
            }
        }
    }

    private static void readSlides(byte[] bytes, Text text) throws IOException {
        try (var slides = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            for (var slide : slides.getSlides()) readShapes(slide.getShapes(), text);
        }
    }

    private static void readShapes(Iterable<XSLFShape> shapes, Text text) {
        for (var shape : shapes) {
            if (shape instanceof XSLFTextShape block) text.line(block.getText());
            else if (shape instanceof XSLFGroupShape group) readShapes(group.getShapes(), text);
            else if (shape instanceof XSLFTable table)
                for (var row : table.getRows()) for (var cell : row.getCells()) text.line(cell.getText());
        }
    }

    private static void readSheet(byte[] bytes, String extension, Text text) throws IOException {
        // 只读显示值或公式缓存，不执行公式，不请求外部链接。
        try (org.apache.poi.ss.usermodel.Workbook book = "xls".equals(extension)
                ? new org.apache.poi.hssf.usermodel.HSSFWorkbook(new ByteArrayInputStream(bytes))
                : new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            for (var sheet : book) for (var row : sheet) {
                for (var cell : row) { text.add(formatter.formatCellValue(cell)); text.add("\t"); }
                text.add("\n");
            }
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        boolean bom = false;
        if (bytes.length >= 3 && bytes[0] == (byte)0xef && bytes[1] == (byte)0xbb && bytes[2] == (byte)0xbf) {
            offset = 3; bom = true;
        } else if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) {
            charset = StandardCharsets.UTF_16LE; offset = 2; bom = true;
        } else if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) {
            charset = StandardCharsets.UTF_16BE; offset = 2; bom = true;
        }
        String result;
        try { result = charset.newDecoder().decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString(); }
        catch (CharacterCodingException e) {
            if (bom) throw e;
            result = Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
        if (result.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw new AgentException("文件不像纯文本文档，请先另存为 UTF-8 文本再试");
        return result;
    }

    private static void readHtml(String html, Text text) throws IOException {
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private int hidden;
            @Override public void handleStartTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet a, int p) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden++;
                if (tag.isBlock()) text.add("\n");
            }
            @Override public void handleEndTag(HTML.Tag tag, int p) {
                if ((tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) && hidden > 0) hidden--;
                if (tag.isBlock()) text.add("\n");
            }
            @Override public void handleSimpleTag(HTML.Tag tag, javax.swing.text.MutableAttributeSet a, int p) {
                if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) text.add("\n");
            }
            @Override public void handleText(char[] data, int p) {
                if (hidden == 0) { text.add(new String(data)); text.add(" "); }
            }
        }, true);
    }

    /** 先检查实际解压大小，再交给 Office 解析器；不相信 ZIP 中声明的文件大小。 */
    private static void checkArchive(byte[] bytes) throws IOException {
        int total = 0, entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > 2000) throw new AgentException("文档内部文件过多，请拆分后导入");
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_ZIP_BYTES) throw new AgentException("文档解压后过大，请拆分或另存为纯文本");
                }
            }
            if (entries == 0) throw new AgentException("文档格式不正确，请重新另存后导入");
        }
    }

    /** 所有格式共用同一字数上限。PDFTextStripper 也可直接写入此 Writer。 */
    private static final class Text extends Writer {
        private final StringBuilder value = new StringBuilder();
        void add(String part) {
            if ((long)value.length() + part.length() > VocabService.MAX_ARTICLE_CHARS)
                throw new AgentException("提取文字超过 100000 字符，请分段导入");
            value.append(part);
        }
        void line(String part) { add(part); add("\n"); }
        @Override public void write(char[] c, int offset, int length) { add(new String(c, offset, length)); }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return value.toString(); }
    }
}
