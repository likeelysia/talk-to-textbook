package io.github.likeelysia.formalagent.memory;

import io.github.likeelysia.formalagent.conversation.ChatMessage;
import java.util.List;

/**
 * 从「一段对话」里挑出值得长期记住的东西。
 *
 * <p>抽成接口(和 {@link SessionSummarizer} 一个道理):一是测试能塞假实现,不必真调模型;
 * 二是以后想换成本地小模型 / 纯规则,换实现就行,调用方不动。
 */
public interface MemoryExtractor {

    /**
     * @param turns 还没提炼过的对话(按时间先后,一条用户消息开启一轮)
     * @return 值得记的候选(已按分数过滤、已去重,带模型给的分);<b>失败返回空表,不抛异常</b>
     *         —— 提炼只是"顺手记笔记",不该让用户问不出问题
     */
    List<MemoryCandidate> extract(List<ChatMessage> turns);
}
