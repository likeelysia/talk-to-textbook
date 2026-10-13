package io.github.likeelysia.formalagent.ocr;

/**
 * OCR 引擎:一张图片 → 一段文字。
 *
 * <p>把「图 → 文」这件事抽成一个接口,好处是<b>换引擎不动调用方</b>:
 * <ul>
 *   <li>{@code vision} —— 远程多模态视觉模型(准,尤其能读公式;但慢,且有限流)。<b>旧名 {@code kimi} 仍作别名认它</b></li>
 *   <li>{@code rapid} —— 本地 PP-OCR(快,离线,零成本;但公式/复杂版式是弱项)</li>
 * </ul>
 *
 * <p>{@link #name()} 给了默认实现,所以它是个函数式接口 —— 测试里打桩可以写成
 * {@code image -> "假文本"} 一行。
 *
 * <p>实现必须做到:<b>读不出内容时抛 {@link io.github.likeelysia.formalagent.exception.AgentException}</b>,
 * 而不是返回空串 —— 空串会被上层当成「这页没内容」静默丢掉;抛异常才会被记进断点的「漏页清单」等下次补灌。
 *
 * <p>入参用 {@code byte[]} 而不是文件路径:页图是 PDFBox 在内存里渲染出来的,
 * 调用方不该被“引擎要不要求落盘”这种实现细节绑住(需要文件路径的引擎自己落临时文件,用完自己删)。
 */
public interface OcrEngine {

    /** 引擎标识({@code vision} / {@code rapid}),用于日志和配置选择 */
    default String name() {
        return "custom";
    }

    /** 识别一张图片(JPEG/PNG 字节)。读不出内容请抛异常,别返回空串。 */
    String ocr(byte[] image);
}
