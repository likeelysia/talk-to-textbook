package io.github.likeelysia.formalagent.conversation;

import java.util.List;

/**
 * 一条「笔记」:用户在自己的提问里勾中的一个问题,连同「AI 对这一问的相关回答」一起存下来。
 *
 * <p>为什么不直接存消息下标:会话以后可能被删/被清空,而下标会变 —— 笔记要能独立活下去,
 * 所以把「问题 + 回答」的**内容**抄一份带走,只在 {@code conversationId} 里留个"来自哪个会话"的线索。
 *
 * @param id             笔记 id(纯 ASCII,前端当按钮的 key)
 * @param conversationId 来源会话(会话删了笔记也还在)
 * @param question       用户的问题原文
 * @param questionAt     这个问题在会话里发生的时间
 * @param answers        AI 对这一问的相关回答(至少一条:同一轮那条)
 * @param savedAt        存进笔记本的时间
 */
public record Note(String id, String conversationId, String question, long questionAt,
                   List<Answer> answers, long savedAt) {

    public Note {
        answers = answers == null ? List.of() : List.copyOf(answers);
    }

    /** 笔记里的一条回答 —— 带时间和工具轨迹,回看时能还原"它当时查了什么"。 */
    public record Answer(String text, long at, List<String> trace) {

        public Answer {
            trace = (trace == null || trace.isEmpty()) ? null : List.copyOf(trace);
        }
    }

    /** 去重用的身份:同一个会话里的同一句提问,只该有一条笔记。 */
    public String dedupKey() {
        return conversationId + "|" + (question == null ? "" : question.strip());
    }
}
