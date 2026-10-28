package io.github.likeelysia.formalagent.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.ocr.RapidOcrEngine;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

/** 本地离线批量 OCR：复用项目引擎，每页留断点，不加载账号/模型或改生产知识库。 */
public final class BookPageOcr {
    private BookPageOcr() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("清单路径和书籍 key 必填");
        Path inventory = Path.of(args[0]).toAbsolutePath();
        ObjectMapper mapper = new ObjectMapper();
        if (args.length >= 3 && "--images".equals(args[2])) {
            ocrImages(inventory.getParent().resolve(args[1]), mapper);
            return;
        }
        JsonNode book = null;
        for (JsonNode row : mapper.readTree(inventory.toFile())) {
            if (row.path("key").asText().equals(args[1])) book = row;
        }
        if (book == null) throw new IllegalArgumentException("未知书籍");
        Path directory = inventory.getParent().resolve(args[1]).resolve("ocr");
        Files.createDirectories(directory);
        RapidOcrEngine ocr = new RapidOcrEngine(Integer.getInteger("ocr.threads", 4), 2400);
        int completed = 0, failed = 0;
        long started = System.nanoTime();
        try (var doc = Loader.loadPDF(Path.of(book.path("path").asText()).toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (JsonNode number : book.path("lowTextPages")) {
                int page = number.asInt();
                if (args.length > 2 && page < Integer.parseInt(args[2])) continue;
                if (args.length > 3 && page > Integer.parseInt(args[3])) continue;
                Path output = directory.resolve(String.format("%04d.json", page));
                if (Files.isRegularFile(output) && mapper.readTree(output.toFile()).has("text")) continue;
                BufferedImage image = renderer.renderImageWithDPI(page - 1, 200);
                long begin = System.nanoTime();
                try {
                    byte[] bytes;
                    try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                        ImageIO.write(image, "jpg", buffer); bytes = buffer.toByteArray();
                    }
                    String text = ocr.ocr(bytes);
                    var result = new LinkedHashMap<String, Object>();
                    result.put("page", page); result.put("text", text); result.put("method", "rapidocr-ppocr-v4");
                    result.put("seconds", (System.nanoTime() - begin) / 1_000_000_000.0);
                    result.put("chinese", text.codePoints().filter(c -> c >= 0x4e00 && c <= 0x9fff).count());
                    Path temp = output.resolveSibling(output.getFileName() + ".tmp");
                    mapper.writeValue(temp.toFile(), result);
                    Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
                    completed++;
                    if (completed % 10 == 0 || completed == 1) {
                        System.out.printf("OCR key=%s page=%d completed=%d elapsedSeconds=%.1f%n",
                                args[1], page, completed, (System.nanoTime() - started) / 1_000_000_000.0);
                    }
                } catch (RuntimeException e) {
                    failed++;
                    mapper.writeValue(output.resolveSibling(output.getFileName()+".failed").toFile(),
                            java.util.Map.of("page", page, "error", e.getClass().getSimpleName()));
                } finally { image.flush(); }
            }
        }
        System.out.printf("OCR_FINISHED key=%s completed=%d failed=%d%n", args[1], completed, failed);
        if (failed != 0) throw new IllegalStateException("仍有 OCR 失败页");
    }

    /** PDFBox 对某些损坏色彩空间渲染为空时，由 PDFium 提供实际可见页图。 */
    private static void ocrImages(Path folder, ObjectMapper mapper) throws Exception {
        Path output = folder.resolve("ocr-refined"); Files.createDirectories(output);
        RapidOcrEngine engine = new RapidOcrEngine(Integer.getInteger("ocr.threads", 2), 2400);
        int count = 0;
        long begin = System.nanoTime();
        try (var paths = Files.list(folder.resolve("rendered"))) {
            for (Path image : paths.filter(p -> p.toString().endsWith(".jpg")).sorted().toList()) {
                int page = Integer.parseInt(image.getFileName().toString().replace(".jpg", ""));
                Path target = output.resolve(String.format("%04d.json", page));
                if (Files.exists(target)) continue;
                String text = engine.ocr(Files.readAllBytes(image));
                Path temp = target.resolveSibling(target.getFileName()+"."+ProcessHandle.current().pid()+".tmp");
                mapper.writeValue(temp.toFile(), java.util.Map.of("page", page, "text", text,
                        "method", "pdfium-rapidocr-ppocr-v4", "chinese", text.codePoints()
                                .filter(c -> c >= 0x4e00 && c <= 0x9fff).count()));
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                count++;
                if (count == 1 || count % 10 == 0) {
                    System.out.printf("OCR_REFINED page=%d completed=%d seconds=%.1f%n", page, count,
                            (System.nanoTime()-begin)/1_000_000_000.0);
                }
            }
        }
        System.out.printf("OCR_REFINED_FINISHED completed=%d%n", count);
    }
}
