package io.github.likeelysia.formalagent.memory;

import io.github.likeelysia.formalagent.conversation.ChatMessage;
import java.util.List;

/**
 * 把「一段旧对话」压成一段话。
 *
 * <p>抽成接口是为了两件事:
 * <ol>
 *   <li><b>可测</b> —— 测试里塞个假实现,不必真去调模型(又慢又要 key);</li>
 *   <li><b>可换</b> —— 以后想换成本地小模型、或纯规则压缩,只换实现,调用方不动。</li>
 * </ol>
 */
public interface SessionSummarizer {

    /**
     * @param previousSummary 上一版摘要(可能为 null / 空 = 第一次压)
     * @param evicted         这一次要被压掉的旧消息(按时间先后)
     * @return 合并后的新摘要
     */
    String summarize(String previousSummary, List<ChatMessage> evicted);
}
