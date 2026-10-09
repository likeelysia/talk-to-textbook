package io.github.likeelysia.formalagent.ocr;

/**
 * OCR 结果「体检」:判断本地 OCR 这一页是<b>真读到了</b>,还是读糊了。
 *
 * <p>为什么需要它:本地 OCR 不会报错,它只会「自信地输出一堆错字」。如果拿着糊掉的结果去提炼知识点,
 * 会往知识库里灌垃圾 —— 比丢页更糟(丢页还能补灌,垃圾条目会污染检索)。
 * 所以本地引擎跑完要过一道体检,不合格就换视觉模型重跑这一页。
 *
 * <p>两个指标,都是<b>便宜的经验值</b>:
 * <ol>
 *   <li><b>有效字数</b> —— 去掉空白后还剩多少字。空白 = 基本没读到东西(图太糊 / 整页是图 / 渲染失败)</li>
 *   <li><b>中文字符占比</b> —— 中文教材的正常页,汉字应占相当比例。占比过低 = 识别成了乱码串</li>
 * </ol>
 */
public final class OcrQuality {

    private OcrQuality() {
    }

    /** 有效字数:去掉所有空白字符后的长度 */
    public static int effectiveChars(String text) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) count++;
        }
        return count;
    }

    /** 汉字个数(按码点判 Unicode 脚本 HAN,连生僻字、扩展区都算) */
    public static int chineseChars(String text) {
        if (text == null) return 0;
        int count = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) count++;
            i += Character.charCount(cp);
        }
        return count;
    }

    /** 汉字占「有效字」的比例(0.0 ~ 1.0);没有有效字时为 0 */
    public static double chineseRatio(String text) {
        int effective = effectiveChars(text);
        if (effective == 0) return 0.0;
        return (double) chineseChars(text) / effective;
    }

    /**
     * 这页读得靠不靠谱?两个条件都满足才算靠谱。
     *
     * @param minChars        至少要有多少有效字
     * @param minChineseRatio 中文占比至少多少(0 = 不检查)
     */
    public static boolean looksReasonable(String text, int minChars, double minChineseRatio) {
        if (effectiveChars(text) < minChars) return false;
        return chineseRatio(text) >= minChineseRatio;
    }
}
