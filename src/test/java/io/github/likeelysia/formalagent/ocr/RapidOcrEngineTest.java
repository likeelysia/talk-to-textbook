package io.github.likeelysia.formalagent.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 本地 OCR 引擎的真刀真枪测试:现画一张图,让它认。
 *
 * <p>这个测试<b>真会加载 native 库 + 27MB 模型</b>(第一次跑约十几秒,之后走静态单例),所以断言挑最稳的:
 * 大号黑字的阿拉伯数字(OCR 对数字几乎不会错)。文字结果会打日志,方便肉眼看质量。
 */
class RapidOcrEngineTest {

    /** 画一张白底黑字的图,返回 JPEG 字节(和生产链路一致:PDFBox 渲染出来也是 JPEG) */
    private static byte[] image(String text) throws Exception {
        BufferedImage img = new BufferedImage(1400, 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setColor(Color.BLACK);
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 72));
            g.drawString(text, 60, 240);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static String cjkOrLatin() {
        Font f = new Font("Microsoft YaHei", Font.BOLD, 72);
        return f.canDisplay('物') ? "第一章 物理 1234" : "OCR TEST 1234";
    }

    @Test
    @DisplayName("认得出来:画一张图 → OCR 出数字(顺带在日志里看看中文识得怎么样)")
    void readsSimpleImage() throws Exception {
        RapidOcrEngine engine = new RapidOcrEngine(0, 0);
        String text = engine.ocr(image(cjkOrLatin()));

        assertNotNull(text);
        System.out.println("[测试] 本地 OCR 结果 = " + text);
        assertTrue(text.contains("1234"), "数字没认出来,实际结果:" + text);
        assertTrue(OcrQuality.effectiveChars(text) >= 4, "有效字太少:" + text);
    }

    @Test
    @DisplayName("白纸一张 → 返回空(引擎不报错;要不要当漏页由上层决定)")
    void blankPageComesBackEmpty() throws Exception {
        RapidOcrEngine engine = new RapidOcrEngine(0, 0);
        String text = engine.ocr(image(" "));
        assertEquals("", text.trim());
    }

    @Test
    @DisplayName("空图 → 直接抛异常,别把锅丢给 native 层")
    void rejectsEmptyInput() {
        assertTrue(assertThrowsAgentException(() -> new RapidOcrEngine(0, 0).ocr(new byte[0])));
    }

    private static boolean assertThrowsAgentException(Runnable runnable) {
        try {
            runnable.run();
            return false;
        } catch (io.github.likeelysia.formalagent.exception.AgentException e) {
            return true;
        }
    }
}
