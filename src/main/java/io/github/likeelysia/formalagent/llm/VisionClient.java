package io.github.likeelysia.formalagent.llm;

import java.nio.file.Path;

/**
 * 视觉客户端:给一张图 + 一段指令,还我模型的文字回复。
 *
 * <p><b>它是「视觉模型」这件事唯一的抽象点</b> —— 调用方(图片入库 {@code ImageReader}、
 * 扫描件 OCR {@code VisionOcrEngine}、图片问答 {@code QaService} / {@code AgentController})
 * 只认这个接口,谁都不知道背后是哪家模型。目前唯一的实现
 * {@link OpenAiCompatibleVisionClient} 走 OpenAI 兼容协议,
 * 端点/模型/密钥/厂商专属字段全部来自配置({@code fa.vision.*})。
 *
 * <p>默认方法给了「显示名 / 模型指纹 / 健康探测」三个口子,跟 {@link EmbeddingClient} 保持对称 ——
 * 这样 {@code /api/health} 能一眼看到「视觉这条链路是哪家、配没配好」,
 * 而不是等用户传完图才发现密钥没配。
 */
public interface VisionClient {

    /** 识别一张图(按指令 {@code prompt} 返回文字)。 */
    String ask(Path image, String prompt);

    /** 显示名(日志 / 健康检查用):Kimi / Qwen-VL / 本地 Ollama…… <b>由配置给,不是代码里写死的</b>。 */
    default String provider() {
        return "unknown";
    }

    /** 模型指纹(健康检查上报用)。 */
    default String modelName() {
        return "unknown";
    }

    /**
     * 轻量健康探测:配置可用 → {@code "UP"},缺端点/模型/密钥 → {@code "DOWN"},不支持探测 → {@code "UNKNOWN"}。
     *
     * <p>语义是「<b>配置可用</b>」而不是「刚才调用成功」—— 它不该真去打一次视觉接口(贵、慢、还费配额)。
     */
    default String health() {
        return "UNKNOWN";
    }
}
