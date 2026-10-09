package io.github.likeelysia.formalagent.ocr;

import java.util.List;
import java.util.Locale;

/**
 * OCR 文本去噪 —— 扫描件常带扫描 App 的页眉/页脚水印(如「夸克扫描王」「极速扫描,就是高效」),
 * 这些东西读进知识库就是纯垃圾条目,必须在**进提炼之前**丢掉。
 *
 * <p>规则很简单:一行只要<b>包含</b>任一关键字,就把<b>整行</b>丢掉(不区分大小写);
 * 关键字可配({@code fa.ocr.watermark-lines},逗号/分号/竖线分隔),**留空 = 不过滤**。
 *
 * <p>为什么要按「行」而不是按「子串」删:水印既可能独占一行(常见),也可能和正文挤在一行;
 * 前者该整行丢,后者如果只抠掉那几个字,剩下的半行反而是残句 —— 不如整行丢。正文一般不会包含这些词。
 */
public final class OcrTextFilter {

    /** 默认关键字:夸克扫描王(App 名)+ 「极速扫描,就是高效」(它的宣传语) */
    public static final List<String> DEFAULT_PATTERNS = List.of("夸克扫描王", "极速扫描", "就是高效");

    private final List<String> patterns;

    public OcrTextFilter(List<String> patterns) {
        this.patterns = patterns == null ? List.of()
                : patterns.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 从配置串构造(逗号 / 分号 / 竖线分隔);空白 = 不过滤 */
    public static OcrTextFilter of(String raw) {
        if (raw == null || raw.isBlank()) {
            return new OcrTextFilter(List.of());
        }
        return new OcrTextFilter(List.of(raw.split("[,;|]")));
    }

    /** 当前生效的关键字(日志/自检用) */
    public List<String> patterns() {
        return patterns;
    }

    /** 丢水印行;没配置关键字 / 文本为空时原样返回 */
    public String clean(String text) {
        if (text == null || text.isBlank() || patterns.isEmpty()) {
            return text;
        }
        StringBuilder kept = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (isWatermark(line)) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('\n');
            }
            kept.append(line);
        }
        return kept.toString();
    }

    private boolean isWatermark(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return patterns.stream()
                .map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(lower::contains);
    }
}
