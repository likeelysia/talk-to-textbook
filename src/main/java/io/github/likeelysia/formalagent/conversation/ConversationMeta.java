package io.github.likeelysia.formalagent.conversation;

/**
 * 会话的"轻量档案":侧栏列表只需要这些,不必把整段聊天记录读出来。
 *
 * <p>这就是 index.json 里存的东西 —— <b>列表页只读它</b>,
 * 所以哪怕某个会话攒了几百条消息,打开侧栏也不会卡。
 */
public record ConversationMeta(
        String id,
        String title,
        String scopeId,
        long createdAt,
        long updatedAt,
        int messageCount) {
}
