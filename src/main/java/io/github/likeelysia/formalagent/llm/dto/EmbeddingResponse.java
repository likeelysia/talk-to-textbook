package io.github.likeelysia.formalagent.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * OpenAI 兼容的 embedding 响应体:{"data":[{"embedding":[...]}, ...]}。
 *
 * <p>只声明我们关心的 data[].embedding;其余字段(id/index/usage 等)一律忽略。
 *
 * <p>⚠️ 这里的 {@code @JsonIgnoreProperties} 不是装饰:llama.cpp 的 /v1/embeddings 每个 item 都会回一个
 * index 字段,真实服务商也可能随时加字段。若解析器恰好是严格模式(例如测试里 new 出来的裸 ObjectMapper),
 * 未知字段会直接抛 UnrecognizedPropertyException —— 而调用方是"吞掉异常并降级",于是检索会**静默**退化成
 * 纯关键词,只表现为"换个说法就搜不到"。把宽容写在 DTO 上,才不会依赖调用方用什么 mapper。
 *
 * @see io.github.likeelysia.formalagent.llm.LocalEmbeddingClient
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(List<Item> data) {

    /** 一条向量结果。Jackson 会把 JSON 数组直接映射成 float[]。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(float[] embedding) {
    }
}
