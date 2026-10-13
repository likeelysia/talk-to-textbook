package io.github.likeelysia.formalagent.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 流式(SSE)返回的一个"增量块":{@code {"choices":[{"delta":{"content":"位"}}]}}。
 *
 * <p>和一次性返回的 {@link ChatResponse} 的区别:那边是 {@code message}(完整消息),
 * 这边是 <b>{@code delta}</b>(这一小片新内容)。服务端用 {@code data: {...}} 一行一条往下推,
 * 最后以 {@code data: [DONE]} 收尾。
 *
 * <p>工具调用在流式下是<b>碎片</b>:函数的 name/arguments 会被拆成很多段分别下发,
 * 每段带一个 {@code index} 用来认领到同一个调用上 —— 这就是 {@link ToolCallDelta} 的由来,
 * 组装工作交给调用方(见 DeepSeekClient)。
 *
 * <p>一律 {@code @JsonIgnoreProperties(ignoreUnknown = true)}:流式响应里字段更多更杂
 * (finish_reason / logprobs / indexing 等),不能因为多认识一个字段就整体炸掉。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatChunk(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Delta delta) {
    }

    /** 这一片新增的内容:正文(可能为空)和/或工具调用碎片。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String content, @JsonProperty("tool_calls") List<ToolCallDelta> toolCalls) {
    }

    /**
     * 工具调用的碎片。
     *
     * @param index    认领用的槽位号 —— 同一个 index 的碎片拼成一个调用
     * @param id       调用 id(通常只在第一片里下发)
     * @param type     固定 "function"
     * @param function 名字和参数碎片
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallDelta(Integer index, String id, String type, Function function) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Function(String name, String arguments) {
        }
    }
}
