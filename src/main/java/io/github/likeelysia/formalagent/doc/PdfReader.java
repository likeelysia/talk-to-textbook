package io.github.likeelysia.formalagent.doc;

import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.ocr.OcrEngines;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PDF 读取器,两种策略:
 * ① 有文字层(出版方原生 PDF)→ 直接按页抽文字;
 * ② 没有文字层(扫描件/影印版)→ 逐页渲染成图片,交给 {@link OcrEngines} 识别。
 * 出处统一标到页码。
 *
 * <p>走哪个 OCR 引擎(本地 RapidOCR / 远程视觉模型)由配置决定,读取器不关心;页间节奏由引擎自己管。
 */
@Component
public class PdfReader implements DocumentReader {

    private static final Logger log = LoggerFactory.getLogger(PdfReader.class);

    /** OCR 渲染精度(DPI):越高越清晰,但图越大越慢 */
    private static final float OCR_DPI = 200f;

    private final OcrEngines ocr;

    public PdfReader(OcrEngines ocr) {
        this.ocr = ocr;
    }

    @Override
    public List<TextSegment> read(Path file) {
        return read(file, 1, Integer.MAX_VALUE);
    }

    @Override
    public int pageCount(Path file) {
        if (file == null) throw new AgentException("文件路径不能为空");
        if (!Files.isRegularFile(file)) throw new AgentException("这不是一个文件:" + file);
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new AgentException("读取 PDF 失败:" + file, e);
        }
    }

    @Override
    public List<TextSegment> read(Path file, int fromPage, int toPage) {
        return readReport(file, fromPage, toPage).segments();
    }

    /**
     * 只读 [fromPage, toPage] 页(从 1 开始,左闭右闭),<b>并把 OCR 失败的页号一并带回来</b>。
     *
     * <p><b>这是灌库断点的地基</b>:一本 406 页的扫描件,不必每次都从第 1 页重 OCR,
     * 可以只读"还没灌过的那 40 页"。页码就是<b>物理页序</b>,和出处 location 完全一致。
     *
     * <p>失败页不会让整段报废(能读到的照常返回),但会被上报 —— 灌库那边记进断点文件,
     * 之后单独补灌这几页,避免"静默丢页"。
     */
    @Override
    public ReadResult readReport(Path file, int fromPage, int toPage) {
        if (file == null) throw new AgentException("文件路径不能为空");
        if (!Files.isRegularFile(file)) throw new AgentException("这不是一个文件:" + file);

        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            int total = doc.getNumberOfPages();
            int from = Math.max(1, fromPage);
            int to = Math.min(total, toPage);
            if (from > to) return ReadResult.empty();               // 空区间:没页可读

            List<TextSegment> segments = extractText(doc, from, to); // ① 先试文字层
            List<Integer> failed = List.of();
            if (segments.isEmpty()) {                                // ② 扫描件兜底:走 OCR
                ReadResult ocr = ocrPages(doc, from, to);
                segments = ocr.segments();
                failed = ocr.failedPages();
            }
            if (segments.isEmpty() && failed.isEmpty()) {
                // 读是读完了,只是一页正文都没有 —— 整段都是空白页 / 封面 / 篇章扉页。
                // 这是正常结果,不是失败:真把它当失败,这些页会永远赖在漏页清单里。
                log.warn("PDF 第 {}~{} 页没有任何正文(文字层为空,OCR 读出来也是空的):{}", from, to, file);
            }
            return new ReadResult(segments, failed);
        } catch (IOException e) {
            throw new AgentException("读取 PDF 失败:" + file, e);
        }
    }

    /** 策略①:按页抽文字层(只抽 [from, to] 页) */
    private List<TextSegment> extractText(PDDocument doc, int from, int to) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<TextSegment> segments = new ArrayList<>();
        for (int p = from; p <= to; p++) {
            stripper.setStartPage(p);
            stripper.setEndPage(p);
            String text = stripper.getText(doc);
            if (!text.isBlank()) segments.add(new TextSegment(text, "第 " + p + " 页"));
        }
        return segments;
    }

    /** 策略②:逐页渲染成 JPEG → OCR(只处理 [from, to] 页)。失败页号随结果返回。 */
    private ReadResult ocrPages(PDDocument doc, int from, int to) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        List<TextSegment> segments = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        int pages = doc.getNumberOfPages();
        for (int i = from - 1; i < to; i++) {
            BufferedImage image = renderer.renderImageWithDPI(i, OCR_DPI);
            try {
                String text = ocr.read(toJpeg(image));           // ← 引擎自己会控速 / 自己会兜底
                if (text.isBlank()) {
                    // 读出来是空的:要么这页本来就是白纸,要么有内容但没读出来 —— 拿页图分辨一下。
                    if (looksBlank(image)) {
                        log.info("[OCR] 第 {}/{} 页没有正文(空白页/篇扉页),跳过", i + 1, pages);
                    } else {
                        failed.add(i + 1);
                        log.warn("[OCR] 第 {} 页上明明有东西却没读出文字,记为漏页,下次补灌", i + 1);
                    }
                    continue;
                }
                log.info("[OCR] 第 {}/{} 页 → {} 字", i + 1, pages, text.trim().length());
                segments.add(new TextSegment(text, "第 " + (i + 1) + " 页"));
            } catch (AgentException e) {                         // ← 单页失败不再拖垮整本
                failed.add(i + 1);
                log.warn("[OCR] 第 {} 页失败,已跳过(之后会补灌):{}", i + 1, e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            log.warn("[OCR] 有 {} 页没成功,交给断点记下来补灌:{}", failed.size(), failed);
        }
        return new ReadResult(segments, failed);
    }

    /**
     * 页图是不是「白纸」:抽样看深色像素(墨迹)占比,低于 2% 就当白的。
     *
     * <p>只用来区分两种情况:OCR 读出来是空的,到底是<b>这页本来就白</b>(封面/空白页/只剩扫描水印的扉页),
     * 还是<b>有内容却没读出来</b>(要记漏页、下次补灌)。
     */
    private static boolean looksBlank(BufferedImage image) {
        int step = 4;
        int dark = 0;
        int total = 0;
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if ((r * 30 + g * 59 + b * 11) / 100 < 200) dark++;   // 灰度 < 200 算墨
                total++;
            }
        }
        return total == 0 || dark * 100.0 / total < 2.0;
    }

    /** BufferedImage → JPEG 字节(在内存里转,不落盘:临时文件会被并发打架,也白白多一次磁盘 I/O) */
    private static byte[] toJpeg(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20)) {
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new AgentException("PDF 页渲染成图片失败:" + e.getMessage(), e);
        }
    }
}
