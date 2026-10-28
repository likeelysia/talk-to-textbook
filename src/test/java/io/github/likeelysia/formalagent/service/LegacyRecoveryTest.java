package io.github.likeelysia.formalagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.account.AccountService;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ConversationStore;
import io.github.likeelysia.formalagent.conversation.Note;
import io.github.likeelysia.formalagent.conversation.NoteStore;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 用真实文件制造中途失败，再用全新服务对象恢复；不碰开发/生产数据。 */
class LegacyRecoveryTest {
    @TempDir Path tmp;
    private final ObjectMapper mapper = new ObjectMapper();

    private LegacyUserDataMigration migration(Path conversations, Path memory) {
        AccountService accounts = mock(AccountService.class);
        when(accounts.adminUserId()).thenReturn("admin_owner");
        AppConfig config = new AppConfig(false, null, null, null, null, null,
                new AppConfig.Conversation(conversations.toString()),
                new AppConfig.Memory(null, new AppConfig.Memory.LongTerm(false,
                        memory.toString(), 1, .5, 3, .9, 10, 0, 100)));
        return new LegacyUserDataMigration(accounts, config, mapper,
                memory.resolve("core.md").toString(), memory.resolve("conflicts.json").toString());
    }

    @Test
    @DisplayName("迁移一半失败不标成功；重试保留源与已编辑目标，只交给管理员")
    void resumesAfterPartialCopyWithoutOverwritingEdits() throws Exception {
        Path conversations = tmp.resolve("conversations"), memory = tmp.resolve("memory");
        Files.createDirectories(memory);
        ConversationStore source = new ConversationStore(mapper, conversations);
        source.ensure("legacy_chat", "OLD_TITLE");
        Files.writeString(conversations.resolve("owners.json"), "{\"legacy_chat\":\"old_admin\"}");
        Files.writeString(memory.resolve("legacy_chat.json"), "{\"version\":1,\"entries\":[]}");
        Files.writeString(memory.resolve("core.md"), "OLD_CORE");
        // conflicts 在会话/记忆/core 复制之后处理：明确制造中途 IO/解析失败。
        Files.writeString(memory.resolve("conflicts.json"), "interrupted-json");
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        for (Path root : List.of(conversations, memory)) {
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) originals.put(file, Files.readAllBytes(file));
            }
        }
        Path owner = conversations.resolve("users/admin_owner");
        Path marker = owner.resolve(".legacy-userdata-v1");
        assertThatThrownBy(() -> migration(conversations, memory).migrate()).isInstanceOf(AgentException.class);
        assertThat(marker).doesNotExist();
        assertThat(owner.resolve("conversations/legacy_chat.json")).exists();
        for (var entry : originals.entrySet()) assertThat(Files.readAllBytes(entry.getKey())).isEqualTo(entry.getValue());

        Files.writeString(owner.resolve("memory/core.md"), "ADMIN_EDITED_CORE");
        ConversationStore target = new ConversationStore(mapper, owner.resolve("conversations"));
        // 使用实际 store 改标题，保证 index 和文件同时更新。
        target.update("legacy_chat", c -> c.renamed("ADMIN_EDITED_TITLE"));
        Files.writeString(memory.resolve("conflicts.json"), "{\"conflicts\":[]}");
        originals.put(memory.resolve("conflicts.json"), Files.readAllBytes(memory.resolve("conflicts.json")));
        migration(conversations, memory).migrate();
        migration(conversations, memory).migrate();
        assertThat(Files.readString(marker)).isEqualTo("v2");
        assertThat(Files.readString(owner.resolve("memory/core.md"))).isEqualTo("ADMIN_EDITED_CORE");
        assertThat(target.load("legacy_chat").orElseThrow().title()).isEqualTo("ADMIN_EDITED_TITLE");
        assertThat(target.list()).hasSize(1);
        assertThat(target.list().get(0).id()).isEqualTo("legacy_chat");
        assertThat(Files.readString(owner.resolve("legacy-owners.json"))).contains("old_admin");
        assertThat(owner.resolve("memory/legacy_chat.json")).exists();
        assertThat(owner.resolve("memory/conflicts.json")).exists();
        try (var owners = Files.list(conversations.resolve("users"))) {
            assertThat(owners.map(p -> p.getFileName().toString()).toList()).containsExactly("admin_owner");
        }
        for (var entry : originals.entrySet()) assertThat(Files.readAllBytes(entry.getKey())).isEqualTo(entry.getValue());
    }

    @Test
    @DisplayName("重建笔记已提交但标记未落盘的中断状态，重试不重复、不覆盖编辑")
    void noteRetryWithMissingMarkerIsIdempotent() throws Exception {
        Path file = tmp.resolve("notes/notes.json");
        NoteStore store = new NoteStore(mapper, file);
        store.addAll(List.of(new Note("old_note", "legacy_chat", "OLD_QUESTION", 1,
                List.of(new Note.Answer("OLD_ANSWER", 2, List.of())), 3)));
        byte[] original = Files.readAllBytes(file);
        Path marker = file.getParent().resolve(".legacy-migrated");
        store.migrateLegacy("admin_owner");
        // 重建“目标提交成功、成功标记尚未写入”的磁盘状态，不声称这是断电实验。
        Files.delete(marker);
        store.update("admin_owner", "old_note", "EDITED_QUESTION", null);
        NoteStore restarted = new NoteStore(mapper, file);
        restarted.migrateLegacy("admin_owner");
        restarted.migrateLegacy("admin_owner");
        assertThat(restarted.list("admin_owner")).hasSize(1);
        assertThat(restarted.list("admin_owner").get(0).question()).isEqualTo("EDITED_QUESTION");
        assertThat(restarted.list("admin_owner").get(0).conversationId()).isEqualTo("legacy_chat");
        assertThat(restarted.list("review_A")).isEmpty();
        assertThat(restarted.list("review_B")).isEmpty();
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(Files.readString(marker)).isEqualTo("v2");
    }
}
