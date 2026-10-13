package io.github.likeelysia.formalagent.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化客户端:文字 → 定长向量。
 *
 * <p>它是"语义检索"的地基:意思相近的文本,向量方向也相近 → 用余弦相似度就能找"最近的邻居",
 * 从而把检索从"找字面"升级为"找意思"。实现可换(本地 llama.cpp / 云端 embedding API)。
 */
public interface EmbeddingClient {

    /** 一段文字 → 一个向量。 */
    float[] embed(String text);

    /** 批量(默认逐条调用;实现可覆盖成"一次请求多条")。 */
    default List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }

    /** 模型指纹:向量空间由模型决定,模型一换旧向量就作废 —— 用它来判断要不要重算。 */
    default String modelName() {
        return "default";
    }

    /**
     * 轻量健康探测:能用返回 {@code "UP"},不能用返回 {@code "DOWN"},不支持探测的实现返回 {@code "UNKNOWN"}。
     *
     * <p>为什么需要它:embedding 一旦挂掉,检索会**静默**降级成纯关键词(只表现为"换个说法就搜不到"),
     * 不主动上报的话没人会发现变差了。注意它应该是"轻量"的 —— 不准真去 embed 一段文本。
     */
    default String health() {
        return "UNKNOWN";
    }
}
