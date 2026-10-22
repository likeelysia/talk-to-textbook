package io.github.likeelysia.formalagent.memory;

import java.util.UUID;

/**
 * 一条「核心档案冲突」:自动提炼发现的新事实,和用户手工维护的核心档案对不上。
 *
 * <p>刻意<b>不改核心档案</b>:核心档案是用户写的,模型只能提议,不能替用户改。
 * 这里只留下一条待确认记录,界面上提示用户"要么改档案,要么忽略"。
 */
public record MemoryConflict(
        String id,
        String scopeId,
        String candidateText,
        String conversationId,
        long createdAt,
        String status) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISMISSED = "dismissed";

    public MemoryConflict {
        id = (id == null || id.isBlank()) ? newId() : id;
        scopeId = scopeId == null ? "" : scopeId;
        candidateText = candidateText == null ? "" : candidateText.strip();
        conversationId = conversationId == null ? "" : conversationId;
        createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        status = (status == null || status.isBlank()) ? STATUS_ACTIVE : status;
    }

    public static MemoryConflict of(String scopeId, String candidateText, String conversationId) {
        return new MemoryConflict(newId(), scopeId, candidateText, conversationId,
                System.currentTimeMillis(), STATUS_ACTIVE);
    }

    public MemoryConflict dismissed() {
        return new MemoryConflict(id, scopeId, candidateText, conversationId, createdAt, STATUS_DISMISSED);
    }

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }

    private static String newId() {
        return "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
