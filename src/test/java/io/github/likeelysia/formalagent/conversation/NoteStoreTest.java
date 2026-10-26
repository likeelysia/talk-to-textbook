package io.github.likeelysia.formalagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("NoteStore 笔记本落盘")
class NoteStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Note note(String id, String conv, String question, String answer) {
        return new Note(id, conv, question, 1000L,
                List.of(new Note.Answer(answer, 1001L, List.of("search_knowledge({})"))), 2000L);
    }

    @Test
    @DisplayName("存 → 读:内容原样回来(含回答和工具轨迹)")
    void roundTrip(@TempDir Path tmp) {
        NoteStore store = new NoteStore(MAPPER, tmp.resolve("notes.json"));
        store.addAll(List.of(note("n1", "c1", "位移是什么?", "位移是位置的改变")));

        List<Note> all = store.list();
        assertThat(all).hasSize(1);
        Note n = all.get(0);
        assertThat(n.id()).isEqualTo("n1");
        assertThat(n.conversationId()).isEqualTo("c1");
        assertThat(n.question()).isEqualTo("位移是什么?");
        assertThat(n.answers()).hasSize(1);
        assertThat(n.answers().get(0).text()).isEqualTo("位移是位置的改变");
        assertThat(n.answers().get(0).trace()).containsExactly("search_knowledge({})");
    }

    @Test
    @DisplayName("去重:同会话同问题只留一条(不同会话的同一句可以各存一条)")
    void dedupe(@TempDir Path tmp) {
        NoteStore store = new NoteStore(MAPPER, tmp.resolve("notes.json"));
        assertThat(store.addAll(List.of(note("n1", "c1", "位移是什么?", "答1")))).hasSize(1);
        assertThat(store.addAll(List.of(note("n2", "c1", "位移是什么?", "答1")))).isEmpty();
        assertThat(store.addAll(List.of(note("n3", "c2", "位移是什么?", "答1")))).hasSize(1);
        assertThat(store.list()).hasSize(2);
    }

    @Test
    @DisplayName("列表:新的在前")
    void newestFirst(@TempDir Path tmp) {
        NoteStore store = new NoteStore(MAPPER, tmp.resolve("notes.json"));
        store.addAll(List.of(new Note("old", "c1", "老问题", 1L, List.of(), 1_000L)));
        store.addAll(List.of(new Note("new", "c1", "新问题", 2L, List.of(), 9_000L)));
        assertThat(store.list()).extracting(Note::id).containsExactly("new", "old");
    }

    @Test
    @DisplayName("删除 / 找不到")
    void delete(@TempDir Path tmp) {
        NoteStore store = new NoteStore(MAPPER, tmp.resolve("notes.json"));
        store.addAll(List.of(note("n1", "c1", "问题", "答")));
        assertThat(store.delete("n1")).isTrue();
        assertThat(store.delete("n1")).isFalse();
        assertThat(store.list()).isEmpty();
    }

    @Test
    @DisplayName("owner 隔离:不同账号各读各的,按 ID 也读不到别人的")
    void ownerIsolation(@TempDir Path tmp) {
        NoteStore store = new NoteStore(MAPPER, tmp.resolve("notes.json"));
        store.addAll("alice", List.of(note("a1", "", "A 的问题", "A 的回答")));
        store.addAll("bob", List.of(note("b1", "", "B 的问题", "B 的回答")));

        assertThat(store.list("alice")).extracting(Note::id).containsExactly("a1");
        assertThat(store.list("bob")).extracting(Note::id).containsExactly("b1");
        assertThat(store.find("alice", "b1")).isEmpty();
        assertThat(store.find("bob", "a1")).isEmpty();
        assertThat(store.delete("alice", "b1")).isFalse();
        assertThat(store.list("bob")).hasSize(1);
    }

    @Test
    @DisplayName("旧笔记本只迁给指定 owner,重复执行不重复")
    void migratesLegacyOnlyToAdmin(@TempDir Path tmp) throws Exception {
        Path legacy = tmp.resolve("notes.json");
        NoteStore store = new NoteStore(MAPPER, legacy);
        store.addAll(List.of(note("old", "c1", "旧问题", "旧回答")));

        assertThat(store.migrateLegacy("admin")).isTrue();
        assertThat(store.list("admin")).extracting(Note::id).containsExactly("old");
        assertThat(store.list("alice")).isEmpty();
        assertThat(store.migrateLegacy("admin")).isFalse();
        assertThat(store.list("admin")).hasSize(1);
        assertThat(Files.exists(legacy)).isTrue();
    }

    @Test
    @DisplayName("目标已存在:同 ID 保留编辑,只补缺失;删除后重跑不复活")
    void mergeKeepsEditedTargetAndDoesNotReviveDeleted(@TempDir Path tmp) throws Exception {
        Path legacy = tmp.resolve("notes.json");
        NoteStore store = new NoteStore(MAPPER, legacy);
        store.addAll(List.of(note("old", "c1", "旧问题", "旧回答")));
        store.addAll("admin", List.of(note("old", "c1", "管理员改过", "新回答")));
        assertThat(store.migrateLegacy("admin")).isFalse();
        assertThat(store.list("admin")).extracting(Note::question).containsExactly("管理员改过");

        // 额外旧条目应补入一次
        store.addAll(List.of(note("extra", "c1", "额外旧问题", "额外旧回答")));
        Files.delete(tmp.resolve(".legacy-migrated"));
        assertThat(store.migrateLegacy("admin")).isTrue();
        assertThat(store.list("admin")).extracting(Note::id).contains("old", "extra");

        assertThat(store.delete("admin", "old")).isTrue();
        assertThat(store.migrateLegacy("admin")).isFalse();
        assertThat(store.list("admin")).extracting(Note::id).doesNotContain("old");
    }

    @Test
    @DisplayName("坏文件:页面读空,但写路径拒绝覆盖且原字节保留")
    void rejectsWriteWhenFileCorrupt(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("notes.json");
        byte[] bad = "{ 这不是 JSON".getBytes(StandardCharsets.UTF_8);
        Files.write(file, bad);
        NoteStore store = new NoteStore(MAPPER, file);
        assertThat(store.list()).isEmpty();
        assertThatThrownBy(() -> store.addAll(List.of(note("n1", "c1", "问题", "答"))))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("拒绝覆盖");
        assertThat(Files.readAllBytes(file)).isEqualTo(bad);
    }

    @Test
    @DisplayName("坏旧库迁移:不标成功,原字节保留;修复后可迁移")
    void corruptLegacyDoesNotMarkMigrated(@TempDir Path tmp) throws Exception {
        Path legacy = tmp.resolve("notes.json");
        byte[] bad = "{ 坏旧库".getBytes(StandardCharsets.UTF_8);
        Files.write(legacy, bad);
        NoteStore store = new NoteStore(MAPPER, legacy);

        assertThatThrownBy(() -> store.migrateLegacy("admin"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("拒绝覆盖");
        assertThat(Files.exists(tmp.resolve(".legacy-migrated"))).isFalse();
        assertThat(Files.readAllBytes(legacy)).isEqualTo(bad);

        Files.writeString(legacy, "{\"notes\":[{\"id\":\"n1\",\"conversationId\":\"c1\","
                + "\"question\":\"问题\",\"questionAt\":1,\"answers\":[],\"savedAt\":2}]}",
                StandardCharsets.UTF_8);
        assertThat(store.migrateLegacy("admin")).isTrue();
        assertThat(store.list("admin")).hasSize(1);
    }

    @Test
    @DisplayName("坏目标迁移:不覆盖目标,原字节保留")
    void corruptTargetIsNotOverwritten(@TempDir Path tmp) throws Exception {
        Path legacy = tmp.resolve("notes.json");
        NoteStore store = new NoteStore(MAPPER, legacy);
        store.addAll(List.of(note("old", "c1", "旧问题", "旧回答")));
        Path target = tmp.resolve("admin").resolve("notes.json");
        Files.createDirectories(target.getParent());
        byte[] bad = "{ 坏目标".getBytes(StandardCharsets.UTF_8);
        Files.write(target, bad);

        assertThatThrownBy(() -> store.migrateLegacy("admin"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("拒绝覆盖");
        assertThat(Files.readAllBytes(target)).isEqualTo(bad);
        assertThat(Files.exists(tmp.resolve(".legacy-migrated"))).isFalse();
    }

    @Test
    @DisplayName("写出来的 JSON 是 {notes:[...]}")
    void fileShape(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("notes.json");
        NoteStore store = new NoteStore(MAPPER, file);
        store.addAll(List.of(note("n1", "c1", "问题", "答")));
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw).contains("\"notes\"").contains("\"n1\"");
    }
}
