package io.github.likeelysia.formalagent.llm;

import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.llm.dto.AssistantTurn;
import io.github.likeelysia.formalagent.llm.dto.ToolDefinition;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 大模型客户端:给我对话历史,还我模型回复。 */
public interface LlmClient {

    /** 带调参的对话(主方法)。 */
    String chat(List<Message> history, ChatOptions options);

    /** 普通对话的便捷重载:走默认参数。 */
    default String chat(List<Message> history) {
        return chat(history, ChatOptions.DEFAULT);
    }

    /**
     * 带工具的对话(Agent 用):把完整消息(含工具执行结果)发出去,拿回"模型这一轮"的回复
     * —— 它可能给最终答案,也可能要求再调工具。
     *
     * <p>不支持工具的实现不必覆盖(默认抛异常)。
     */
    default AssistantTurn chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
        throw new AgentException("当前 LlmClient 实现不支持工具调用");
    }

    /**
     * 流式版:{@code onDelta} 每收到一小段正文就回调一次,返回值仍是"这一轮的完整回复"
     * (正文 + 工具调用),所以 Agent 主循环的判断逻辑不用分两套。
     *
     * <p>默认实现 = <b>优雅降级</b>:先用非流式拿到整段,再一次性回调给调用方。
     * 好处是"不支持流式的实现"也能被 {@code runStream} 正确驱动,只是没有逐字效果。
     *
     * <p><b>重试纪律</b>(实现方必须遵守):只允许在"还一个字都没吐出去"时重试。
     * 已经回调过 {@code onDelta} 再重试,用户会看到两遍半截回答。
     */
    default AssistantTurn chatWithToolsStream(List<AgentMessage> messages, List<ToolDefinition> tools,
                                              Consumer<String> onDelta) {
        AssistantTurn turn = chatWithTools(messages, tools);
        if (turn.content() != null && !turn.content().isEmpty()) {
            onDelta.accept(turn.content());
        }
        return turn;
    }

    /**
     * 带取消信号的流式版。默认实现忽略取消信号,保持旧实现/假客户端兼容;
     * 真实客户端会在读循环里检查,断开后尽快停止下游读取。
     */
    default AssistantTurn chatWithToolsStream(List<AgentMessage> messages, List<ToolDefinition> tools,
                                              Consumer<String> onDelta, BooleanSupplier cancelled) {
        return chatWithToolsStream(messages, tools, onDelta);
    }
}
