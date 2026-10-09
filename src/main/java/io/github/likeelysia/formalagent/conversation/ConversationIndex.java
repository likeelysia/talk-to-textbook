package io.github.likeelysia.formalagent.conversation;

import java.util.List;

/**
 * index.json 的顶层结构。
 *
 * <p>为什么不直接写一个裸数组 {@code [ ... ]}?
 * 因为裸数组<b>没法再加东西</b> —— 以后想加 「version」「lastOpenedId」 就无处可放。
 * 包一层对象,等于给未来的自己留了扩展位(这是很便宜的未雨绸缪)。
 */
public record ConversationIndex(List<ConversationMeta> conversations) {

    public ConversationIndex {
        conversations = conversations == null ? List.of() : List.copyOf(conversations);
    }

    public static ConversationIndex empty() {
        return new ConversationIndex(List.of());
    }
}
