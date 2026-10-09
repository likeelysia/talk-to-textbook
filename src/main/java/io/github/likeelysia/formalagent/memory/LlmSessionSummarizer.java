package io.github.likeelysia.formalagent.memory;

import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.conversation.ChatMessage;
import io.github.likeelysia.formalagent.llm.LlmClient;
import io.github.likeelysia.formalagent.prompt.Prompts;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 用大模型做摘要(默认实现)。
 *
 * <p>提示词同样外置在 {@code resources/prompts/session-summarize.txt} —— 摘要要留什么、
 * 扔什么,是要反复调的文案,不该焊在 Java 里。
 *
 * <p>注意:这里走的是 {@link LlmClient#chat} 这条<b>普通对话</b>通道(不带工具),
 * 和 Agent 那条链路互不干扰 —— 摘要本身不需要查知识库。
 */
@Component
public class LlmSessionSummarizer implements SessionSummarizer {

    private final LlmClient llm;

    public LlmSessionSummarizer(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public String summarize(String previousSummary, List<ChatMessage> evicted) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", Prompts.get("session-summarize")));
        messages.add(new Message("user", payload(previousSummary, evicted)));
        return llm.chat(messages);
    }

    /** 给模型的输入:「已有摘要」+「这一段要被压掉的旧对话」。 */
    private static String payload(String previousSummary, List<ChatMessage> evicted) {
        StringBuilder sb = new StringBuilder();
        sb.append("【已有摘要】\n");
        sb.append(previousSummary == null || previousSummary.isBlank() ? "(还没有)" : previousSummary);
        sb.append("\n\n【这一段新的旧对话】\n");
        for (ChatMessage m : evicted) {
            sb.append(m.fromUser() ? "用户:" : "助手:")
                    .append(m.content() == null ? "" : m.content())
                    .append('\n');
        }
        return sb.toString();
    }
}
