package io.github.likeelysia.formalagent.ocr;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OCR 引擎选择器 —— 调用方只说「读这一页」,选谁读、要不要换个引擎重读,都由它决定。
 *
 * <p>工作方式(默认配置:{@code engine=kimi}、{@code fallback-to-kimi=true}):
 * <ul>
 *   <li>主引擎 {@code kimi}:直接读</li>
 *   <li>主引擎 {@code rapid}:先本地读一遍(快)。<b>读得可疑</b>(字太少 / 中文占比过低)或<b>直接失败</b>
 *       → 换 {@code kimi} 把这一页重读一遍(准)。这就是「本地优先 + 差页回退」</li>
 * </ul>
 *
 * <p>为什么值得这么绕:本地 OCR 把 406 页从 2 小时压到十几分钟,但公式页会读糊;
 * 而公式恰恰是视觉模型的强项。于是「便宜的先上、贵的兜底」——绝大多数页省钱省时,少数难页保住质量。
 *
 * <p>兜底也可以关掉({@code fa.ocr.fallback-to-kimi=false}),那样本地读糊的页会原样入库 —— 除非你确定不需要质量。
 *
 * <p>不管哪个引擎读的,结果都会先过一次 {@link OcrTextFilter}(丢扫描 App 的水印行)再体检/返回。
 */
@Component
public class OcrEngines {

    private static final Logger log = LoggerFactory.getLogger(OcrEngines.class);

    private final OcrEngine primary;

    /** 兜底引擎;没有就是 null(主引擎是 kimi 时,或者显式关掉了) */
    private final OcrEngine fallback;

    private final int minChars;
    private final double minChineseRatio;

    /** 文本去噪(水印行) */
    private final OcrTextFilter filter;

    public OcrEngines(List<OcrEngine> engines,
                      @Value("${fa.ocr.engine:kimi}") String engineName,
                      @Value("${fa.ocr.fallback-to-kimi:true}") boolean fallbackToKimi,
                      @Value("${fa.ocr.quality.min-chars:30}") int minChars,
                      @Value("${fa.ocr.quality.min-chinese-ratio:0.2}") double minChineseRatio,
                      @Value("${fa.ocr.watermark-lines:}") String watermarkLines) {
        this.primary = require(engines, engineName);
        this.minChars = minChars;
        this.minChineseRatio = minChineseRatio;
        this.filter = OcrTextFilter.of(watermarkLines);

        OcrEngine kimi = find(engines, "kimi");
        if (!fallbackToKimi || kimi == null || kimi == primary) {
            this.fallback = null;
        } else {
            this.fallback = kimi;
        }
        log.info("OCR 引擎:主 = {}{}", primary.name(),
                fallback == null ? "(无兜底)" : ",兜底 = " + fallback.name()
                        + "(有效字 < " + minChars + " 或中文占比 < " + minChineseRatio + " 时改用它)");
        log.info("OCR 去噪:丢含 {} 的整行", filter.patterns().isEmpty() ? "(未启用)" : filter.patterns());
    }

    /** 测试/手工构造用:单个引擎,不做兜底 */
    public static OcrEngines single(OcrEngine engine) {
        return new OcrEngines(List.of(engine), engine.name(), false, 0, 0.0, "");
    }

    /**
     * 读一页。三种结果分得很清楚:
     * <ul>
     *   <li>读到正文 → 返回文本</li>
     *   <li><b>空读</b>(白纸页 / 封面 / 只剩扫描水印的篇扉页)→ 返回空串。这是<b>正常结果,不是失败</b>;
     *       上层会拿页图再确认一次到底是不是白纸</li>
     *   <li>引擎调用出错(网络 / 额度 / 超时)→ 抛 {@link AgentException},上层记「漏页」下次补灌</li>
     * </ul>
     */
    public String read(byte[] image) {
        if (fallback == null) {
            return filter.clean(primary.ocr(image));       // 可能是空串 —— 那就是白纸页
        }
        try {
            String text = filter.clean(primary.ocr(image));
            if (OcrQuality.looksReasonable(text, minChars, minChineseRatio)) {
                return text;
            }
            log.warn("[OCR] {} 这页读得可疑(有效 {} 字 / 中文占比 {}%),改用 {} 重读",
                    primary.name(), OcrQuality.effectiveChars(text),
                    String.format("%.0f%%", OcrQuality.chineseRatio(text) * 100), fallback.name());
        } catch (RuntimeException e) {
            log.warn("[OCR] {} 这页失败({}),改用 {} 重读", primary.name(), e.getMessage(), fallback.name());
        }
        return filter.clean(fallback.ocr(image));
    }

    /** 主引擎名字(日志用) */
    public String primaryName() {
        return primary.name();
    }

    private static OcrEngine require(List<OcrEngine> engines, String name) {
        OcrEngine found = find(engines, name);
        if (found == null) {
            throw new AgentException("不认识的 OCR 引擎 [" + name + "],可选:"
                    + engines.stream().map(OcrEngine::name).collect(Collectors.joining(" / ")));
        }
        return found;
    }

    private static OcrEngine find(List<OcrEngine> engines, String name) {
        return engines.stream()
                .filter(e -> e.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
