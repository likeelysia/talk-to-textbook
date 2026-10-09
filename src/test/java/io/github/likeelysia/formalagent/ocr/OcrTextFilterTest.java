package io.github.likeelysia.formalagent.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OcrTextFilter}:把扫描 App 的水印行丢掉。
 *
 * <p>真实场景是《大学物理学》那种影印页 —— 每一页都有两行「夸克扫描王 / 极速扫描,就是高效」,
 * 不处理就会变成 406 × 2 行纯垃圾灌进知识库。
 */
class OcrTextFilterTest {

    private static final OcrTextFilter DEFAULT =
            new OcrTextFilter(OcrTextFilter.DEFAULT_PATTERNS);

    @Test
    @DisplayName("水印行整行丢掉,正文一行不少、顺序不变")
    void dropsWatermarkKeepsBody() {
        String raw = "第一章 质点运动学\n夸克扫描王\n位移是矢量。\n极速扫描，就是高效";
        assertEquals("第一章 质点运动学\n位移是矢量。", DEFAULT.clean(raw));
    }

    @Test
    @DisplayName("水印和正文挤在一行 → 整行丢(留半行残句不如不留)")
    void dropsLineContainingWatermark() {
        assertEquals("位移是矢量。", DEFAULT.clean("位移是矢量。\n扫描:夸克扫描王 123"));
    }

    @Test
    @DisplayName("没有水印的文本原样返回")
    void leavesCleanTextUntouched() {
        String raw = "第二章 牛顿运动定律\n力是物体间的相互作用。";
        assertEquals(raw, DEFAULT.clean(raw));
    }

    @Test
    @DisplayName("配置留空 = 不过滤(连水印也不动)")
    void emptyConfigMeansNoFiltering() {
        OcrTextFilter off = OcrTextFilter.of("   ");
        assertTrue(off.patterns().isEmpty());
        assertEquals("夸克扫描王", off.clean("夸克扫描王"));
    }

    @Test
    @DisplayName("关键字可配(逗号 / 分号 / 竖线都行),自定义关键字生效")
    void supportsCustomPatterns() {
        assertEquals("正文", OcrTextFilter.of("某扫描App;另一款|水印").clean("正文\n由某扫描App生成"));
        assertEquals("正文", OcrTextFilter.of("水印,某扫描App").clean("另一款水印\n正文"));
    }

    @Test
    @DisplayName("null / 空串原样返回,不抛异常")
    void nullSafe() {
        assertNull(DEFAULT.clean(null));
        assertEquals("   ", DEFAULT.clean("   "));
    }

    @Test
    @DisplayName("关键字用列表构造时也会去空白(配置文件里手写的空格不算关键字)")
    void trimsConfiguredPatterns() {
        assertEquals(List.of("夸克扫描王"), new OcrTextFilter(List.of("  夸克扫描王 ")).patterns());
    }
}
