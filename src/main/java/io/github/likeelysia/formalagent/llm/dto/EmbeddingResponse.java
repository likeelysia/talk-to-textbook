package io.github.likeelysia.formalagent.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * OpenAI 兼容的 embedding 响应体:{"data":[{"index":0,"embedding":[...]}], "usage":{...}}。
 *
 * <p>只声明我们关心的字段；服务商可能附加 model/object 等字段，必须容忍。
 * data[].index 是批量顺序的契约，客户端会校验并按其还原顺序。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(List<Item> data, Usage usage) {

    /** 一条向量结果。Jackson 会把 JSON 数组直接映射成 float[]。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Integer index, float[] embedding) {
    }

    /** 部分服务商返回的用量；没有返回时为 null，不能把它当作 0 账单。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(Integer prompt_tokens, Integer total_tokens) {
    }
}
