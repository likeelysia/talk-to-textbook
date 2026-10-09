package io.github.likeelysia.formalagent.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link OcrQuality} 「体检」规则:本地 OCR 读糊了要能识别出来,好让上层换引擎重跑。 */
class OcrQualityTest {

    @Test
    @DisplayName("有效字数:空白字符不算")
    void countsEffectiveChars() {
        assertEquals(0, OcrQuality.effectiveChars(null));
        assertEquals(0, OcrQuality.effectiveChars("   \n\t "));
        assertEquals(4, OcrQuality.effectiveChars(" 质 点\n 运 动 "));
    }

    @Test
    @DisplayName("汉字计数:按 Unicode HAN 脚本判,标点/数字/字母都不算")
    void countsChinese() {
        assertEquals(5, OcrQuality.chineseChars("质点运动学 123 abc,。!"));   // 质点运动学 = 5 个汉字
        assertEquals(0, OcrQuality.chineseChars("plain ascii only"));
        assertEquals(0, OcrQuality.chineseChars(null));
    }

    @Test
    @DisplayName("中文占比:汉字 / 有效字(标点也算进分母,所以别把分母当汉字数)")
    void computesRatio() {
        assertEquals(0.0, OcrQuality.chineseRatio(""));
        assertEquals(0.4, OcrQuality.chineseRatio("质点abc"), 1e-9);            // 2 汉字 / 5 有效字
    }

    @Test
    @DisplayName("正常中文页 = 靠谱")
    void reasonableForNormalPage() {
        String page = "第一章 质点运动学。本章研究物体的位置随时间变化的规律,包含位移、速度和加速度三个基本概念。";
        assertTrue(OcrQuality.looksReasonable(page, 30, 0.2));
    }

    @Test
    @DisplayName("读糊了(乱码串 / 空结果)= 不靠谱 → 该换引擎")
    void unreasonableForGarbage() {
        assertFalse(OcrQuality.looksReasonable("", 30, 0.2));
        assertFalse(OcrQuality.looksReasonable("   ~~~~ ## !!", 30, 0.2));          // 字太少
        assertFalse(OcrQuality.looksReasonable("ab12cd34ef56gh78ij90kl12mn34op56", 30, 0.2)); // 全英文
    }

    @Test
    @DisplayName("阈值可配:min-chars=0 / ratio=0 时放得很宽")
    void thresholdsAreConfigurable() {
        assertTrue(OcrQuality.looksReasonable("abc", 0, 0.0));
        assertFalse(OcrQuality.looksReasonable("ab", 3, 0.0));
    }
}
