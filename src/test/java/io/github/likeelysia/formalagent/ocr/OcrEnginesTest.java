package io.github.likeelysia.formalagent.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OcrEngines} 的选择 / 兜底规则 —— 「本地优先 + 差页回退视觉模型」的中枢。
 *
 * <p>关键不是「能跑」,而是<b>什么时候不该跑</b>:
 * 好页不能白跑一次视觉(浪费配额),糊页不能硬着头皮用。
 *
 * <p>引擎名已去厂商化:{@code vision} 是视觉引擎的本名,旧的 {@code kimi} / {@code moonshot} 作为<b>别名</b>继续认
 * (老配置 {@code FA_OCR_ENGINE=kimi} 不必改)。
 */
class OcrEnginesTest {

    private static final String GOOD = "第一章 质点运动学。本章研究物体位置随时间变化的规律,包含位移、速度与加速度。";
    private static final String GARBAGE = "~~~ ##";
    private static final String WATERMARK = "夸克扫描王\n极速扫描，就是高效";

    private static final String VISION = VisionOcrEngine.NAME;   // "vision"
    private static final String RAPID = "rapid";

    /** 假引擎:固定返回一段文本,或者一被调用就炸;顺便数调用次数 */
    private static final class Fake implements OcrEngine {
        private final String name;
        private final String result;
        private int calls;

        Fake(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override public String name() { return name; }

        @Override public String ocr(byte[] image) {
            calls++;
            if (result == null) throw new AgentException(name + " 故意失败");
            return result;
        }
    }

    /** 快捷构造:主引擎 + 兜底引擎名(默认 vision),不带水印过滤 */
    private static OcrEngines select(List<OcrEngine> engines, String primary, boolean fallback,
                                     int minChars, double minRatio) {
        return select(engines, primary, fallback, VISION, minChars, minRatio, "");
    }

    private static OcrEngines select(List<OcrEngine> engines, String primary, boolean fallback,
                                     String fallbackEngine, int minChars, double minRatio, String watermarkLines) {
        return new OcrEngines(engines, primary, fallback, fallbackEngine, minChars, minRatio, watermarkLines);
    }

    @Test
    @DisplayName("单引擎(无兜底):读出什么就返回什么")
    void singleEnginePassesThrough() {
        Fake only = new Fake(RAPID, GOOD);
        assertEquals(GOOD, OcrEngines.single(only).read(new byte[]{1}));
        assertEquals(1, only.calls);
    }

    @Test
    @DisplayName("单引擎读出空 → 返回空(白纸页是正常结果,不是失败)")
    void singleEngineReturnsBlank() {
        assertTrue(OcrEngines.single(new Fake(RAPID, "   ")).read(new byte[]{1}).isBlank());
    }

    @Test
    @DisplayName("本地读得好 → 不惊动兜底引擎(省配额)")
    void doesNotFallBackWhenGood() {
        Fake rapid = new Fake(RAPID, GOOD);
        Fake vision = new Fake(VISION, GOOD);
        assertEquals(GOOD, select(List.of(rapid, vision), RAPID, true, 30, 0.2).read(new byte[]{1}));
        assertEquals(1, rapid.calls);
        assertEquals(0, vision.calls);
    }

    @Test
    @DisplayName("本地读糊了(字太少)→ 换兜底重读")
    void fallsBackWhenSuspicious() {
        Fake rapid = new Fake(RAPID, GARBAGE);
        Fake vision = new Fake(VISION, GOOD);
        assertEquals(GOOD, select(List.of(rapid, vision), RAPID, true, 30, 0.2).read(new byte[]{1}));
        assertEquals(1, rapid.calls);
        assertEquals(1, vision.calls);
    }

    @Test
    @DisplayName("本地直接失败(native 库没起来 / 图解不开)→ 也换兜底")
    void fallsBackWhenPrimaryThrows() {
        Fake rapid = new Fake(RAPID, null);
        Fake vision = new Fake(VISION, GOOD);
        assertEquals(GOOD, select(List.of(rapid, vision), RAPID, true, 30, 0.2).read(new byte[]{1}));
        assertEquals(1, rapid.calls);
        assertEquals(1, vision.calls);
    }

    @Test
    @DisplayName("兜底也没读出来 → 返回空串(交给上层拿页图判断是不是白纸)")
    void blankWhenFallbackBlankToo() {
        OcrEngines engines = select(
                List.of(new Fake(RAPID, GARBAGE), new Fake(VISION, "")), RAPID, true, 30, 0.2);
        assertEquals("", engines.read(new byte[]{1}));
    }

    @Test
    @DisplayName("主引擎就是视觉引擎时,不会再拿它当自己的兜底")
    void visionIsNotItsOwnFallback() {
        Fake vision = new Fake(VISION, GARBAGE);   // 糊了也不重试
        OcrEngines engines = select(List.of(vision), VISION, true, 30, 0.2);
        assertEquals(GARBAGE, engines.read(new byte[]{1}));
        assertEquals(1, vision.calls);
    }

    @Test
    @DisplayName("兜底可关:关掉后本地读糊的结果原样返回")
    void fallbackCanBeDisabled() {
        Fake rapid = new Fake(RAPID, GARBAGE);
        Fake vision = new Fake(VISION, GOOD);
        OcrEngines engines = select(List.of(rapid, vision), RAPID, false, 30, 0.2);
        assertEquals(GARBAGE, engines.read(new byte[]{1}));
        assertEquals(0, vision.calls);
    }

    @Test
    @DisplayName("旧名 kimi / moonshot 是别名:老配置照样认到 vision 引擎")
    void legacyNamesResolveToVisionEngine() {
        for (String legacy : List.of("kimi", "moonshot", "KIMI")) {
            Fake rapid = new Fake(RAPID, GARBAGE);
            Fake vision = new Fake(VISION, GOOD);
            // 主引擎就配成旧名 → 应该找到 vision 那个引擎,并且它不该当自己的兜底
            assertEquals(GOOD, select(List.of(rapid, vision), legacy, true, 30, 0.2).read(new byte[]{1}));
            assertEquals(1, vision.calls);

            // 兜底引擎配成旧名 → 也能找到 vision
            Fake rapid2 = new Fake(RAPID, GARBAGE);
            Fake vision2 = new Fake(VISION, GOOD);
            assertEquals(GOOD, select(List.of(rapid2, vision2), RAPID, true, legacy, 30, 0.2, "").read(new byte[]{1}));
            assertEquals(1, vision2.calls);
        }
    }

    @Test
    @DisplayName("兜底引擎的名字也是配置:配了个不存在的 → 就当没有兜底,不报错")
    void unknownFallbackEngineMeansNoFallback() {
        Fake rapid = new Fake(RAPID, GARBAGE);
        OcrEngines engines = select(List.of(rapid), RAPID, true, "qwen-vl", 30, 0.2, "");
        assertEquals(GARBAGE, engines.read(new byte[]{1}));
        assertEquals(1, rapid.calls);
    }

    @Test
    @DisplayName("配置写了个不存在的引擎名 → 启动就报错,别等跑起来才发现")
    void rejectsUnknownEngine() {
        AgentException e = assertThrows(AgentException.class,
                () -> select(List.of(new Fake(VISION, GOOD)), RAPID, true, RAPID, 30, 0.2, ""));
        assertTrue(e.getMessage().contains(RAPID));
    }

    @Test
    @DisplayName("primaryName 暴露给日志")
    void exposesPrimaryName() {
        assertSame(RAPID, select(List.of(new Fake(RAPID, GOOD)), RAPID, false, 0, 0.0).primaryName());
    }

    @Test
    @DisplayName("水印行在体检/返回之前就被剔掉(不是原样进知识库)")
    void stripsWatermarkLines() {
        Fake vision = new Fake(VISION, WATERMARK + "\n" + GOOD);
        String text = select(List.of(vision), VISION, false, VISION, 30, 0.2,
                "夸克扫描王,极速扫描,就是高效").read(new byte[]{1});
        assertEquals(GOOD, text);
        assertEquals(1, vision.calls);
    }

    @Test
    @DisplayName("整页只有水印 → 等于没读出内容 → 返回空串(是不是白纸由上层看页图定)")
    void pageWithOnlyWatermarkCountsAsBlank() {
        Fake rapid = new Fake(RAPID, WATERMARK);
        Fake vision = new Fake(VISION, WATERMARK);
        OcrEngines engines = select(List.of(rapid, vision), RAPID, true, VISION, 30, 0.2,
                "夸克扫描王,极速扫描,就是高效");
        assertEquals("", engines.read(new byte[]{1}));
        assertEquals(1, rapid.calls);   // 本地先读 → 被去噪成空 → 判可疑 → 才轮到兜底
        assertEquals(1, vision.calls);
    }
}
