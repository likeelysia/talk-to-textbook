package io.github.likeelysia.formalagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ConversationOwnershipStore 归属文件")
class ConversationOwnershipStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("文件不存在:旧库兼容只给 admin;claim 后未知 ID 不再默认 admin")
    void missingFileLegacyThenClaim(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("owners.json");
        ConversationOwnershipStore store = new ConversationOwnershipStore(MAPPER, file.toString());
        assertThat(store.canAccess("c1", "admin")).isTrue();
        assertThat(store.canAccess("c1", "bob")).isFalse();

        store.claim("c1", "ubob");
        assertThat(store.canAccess("c1", "ubob")).isTrue();
        assertThat(store.canAccess("c1", "admin")).isFalse();
        assertThat(store.canAccess("c2", "admin")).isFalse();

        ConversationOwnershipStore reloaded = new ConversationOwnershipStore(MAPPER, file.toString());
        assertThat(reloaded.canAccess("c1", "ubob")).isTrue();
        assertThat(reloaded.canAccess("c1", "admin")).isFalse();
    }

    @Test
    @DisplayName("损坏归属文件 fail-closed,不覆盖原件,不能 claim")
    void corruptFileFailsClosed(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("owners.json");
        byte[] bad = "{ 坏归属".getBytes(StandardCharsets.UTF_8);
        Files.write(file, bad);

        ConversationOwnershipStore store = new ConversationOwnershipStore(MAPPER, file.toString());
        assertThat(store.canAccess("private-b", "admin")).isFalse();
        assertThat(store.canAccess("private-b", "ubob")).isFalse();
        assertThatThrownBy(() -> store.claim("private-b", "ubob"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("不可信");
        assertThat(Files.readAllBytes(file)).isEqualTo(bad);
    }

    @Test
    @DisplayName("有效空归属文件不等于旧库:未知 ID 不再给 admin")
    void validEmptyFileDoesNotFallbackToAdmin(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("owners.json");
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        ConversationOwnershipStore store = new ConversationOwnershipStore(MAPPER, file.toString());
        assertThat(store.canAccess("c1", "admin")).isFalse();
    }
}
