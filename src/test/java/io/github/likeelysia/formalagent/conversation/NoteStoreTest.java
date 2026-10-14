package io.github.likeelysia.formalagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    @DisplayName("坏文件不该让笔记本打不开")
    void toleratesBrokenFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("notes.json");
        Files.writeString(file, "{ 这不是 JSON", StandardCharsets.UTF_8);
        NoteStore store = new NoteStore(MAPPER, file);
        assertThat(store.list()).isEmpty();
        assertThat(store.addAll(List.of(note("n1", "c1", "问题", "答")))).hasSize(1);
        assertThat(store.list()).hasSize(1);
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
