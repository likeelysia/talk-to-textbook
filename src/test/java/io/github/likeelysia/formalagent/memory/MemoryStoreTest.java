package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 长期记忆落盘:一个记忆域一个 JSON,读改写一把锁,路径穿越挡在门外。 */
@DisplayName("MemoryStore 长期记忆落盘")
class MemoryStoreTest {

    @TempDir
    Path tmp;

    private MemoryStore store() {
        return new MemoryStore(new ObjectMapper(), tmp, "fake-model");
    }

    @Test
    @DisplayName("没档可读时是空表(不是报错)")
    void missingFileIsEmpty() {
        assertThat(store().list("scope1")).isEmpty();
    }

    @Test
    @DisplayName("写进去能读回来:文字、时间、向量都在")
    void roundTrip() {
        MemoryStore store = store();
        store.update("scope1", list -> List.of(
                MemoryEntry.of("scope1", "用户在准备物理考试", "c1", new float[] {1, 0})));

        List<MemoryEntry> back = store.list("scope1");
        assertThat(back).hasSize(1);
        assertThat(back.get(0).text()).isEqualTo("用户在准备物理考试");
        assertThat(back.get(0).scopeId()).isEqualTo("scope1");
        assertThat(back.get(0).conversationId()).isEqualTo("c1");
        assertThat(back.get(0).hasEmbedding()).isTrue();
        assertThat(back.get(0).embedding()).containsExactly(1f, 0f);
    }

    @Test
    @DisplayName("追加不会丢掉之前那条(读改写一把梭)")
    void updateIsReadModifyWrite() {
        MemoryStore store = store();
        store.update("scope1", list -> List.of(MemoryEntry.of("scope1", "第一条", "c1", null)));
        store.update("scope1", list -> {
            List<MemoryEntry> next = new ArrayList<>(list);
            next.add(MemoryEntry.of("scope1", "第二条", "c1", null));
            return next;
        });

        assertThat(store.list("scope1")).extracting(MemoryEntry::text).containsExactly("第一条", "第二条");
    }

    @Test
    @DisplayName("不同记忆域互不干扰")
    void scopesAreIsolated() {
        MemoryStore store = store();
        store.update("a", list -> List.of(MemoryEntry.of("a", "A 域的记忆", "c1", null)));
        store.update("b", list -> List.of(MemoryEntry.of("b", "B 域的记忆", "c2", null)));

        assertThat(store.list("a")).extracting(MemoryEntry::text).containsExactly("A 域的记忆");
        assertThat(store.list("b")).extracting(MemoryEntry::text).containsExactly("B 域的记忆");
    }

    @Test
    @DisplayName("文件坏了不影响聊天:当它没有记忆")
    void corruptFileIsIgnored() throws IOException {
        Files.writeString(tmp.resolve("bad.json"), "{ 这不是 JSON");
        assertThat(store().list("bad")).isEmpty();
    }

    @Test
    @DisplayName("换了 embedding 模型 -> 旧向量作废(留着文字,不带向量用)")
    void vectorsAreDroppedWhenModelChanges() {
        new MemoryStore(new ObjectMapper(), tmp, "old-model")
                .update("scope1", list -> List.of(MemoryEntry.of("scope1", "老模型算的记忆", "c1", new float[] {1, 1})));

        List<MemoryEntry> back = store().list("scope1");       // fake-model 来读

        assertThat(back).hasSize(1);
        assertThat(back.get(0).text()).isEqualTo("老模型算的记忆");
        assertThat(back.get(0).hasEmbedding()).isFalse();
    }

    @Test
    @DisplayName("记忆域 id 是安全边界:../ 直接被挡")
    void rejectsPathTraversalScope() {
        assertThatThrownBy(() -> store().list("../../evil"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("非法");
    }

    @Test
    @DisplayName("delete:整个记忆域清掉(用户说'忘掉'就得真忘掉)")
    void deleteClearsScope() {
        MemoryStore store = store();
        store.update("scope1", list -> List.of(MemoryEntry.of("scope1", "要忘掉的", "c1", null)));
        store.delete("scope1");

        assertThat(store.list("scope1")).isEmpty();
        assertThat(Files.exists(tmp.resolve("scope1.json"))).isFalse();
    }

    @Test
    @DisplayName("写出去的 JSON 字段就那几个:不会多出 hasEmbedding 这种幽灵字段")
    void jsonHasNoGhostFields() throws IOException {
        store().update("scope1", list -> List.of(MemoryEntry.of("scope1", "记忆一", "c1", new float[] {1})));

        JsonNode root = new ObjectMapper().readTree(Files.readString(tmp.resolve("scope1.json")));
        assertThat(root.get("model").asText()).isEqualTo("fake-model");

        List<String> fields = new ArrayList<>();
        root.get("entries").get(0).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "id", "scopeId", "text", "conversationId", "createdAt", "lastAccessAt",
                "pinned", "origin", "embedding", "updatedAt", "status", "supersedes");
    }

    @Test
    @DisplayName("老文件(没有 pinned / origin / lastAccessAt 字段)照样读得回来")
    void legacyFileWithoutNewFields() throws IOException {
        Files.writeString(tmp.resolve("old.json"), """
                {"model":"fake-model","entries":[
                  {"id":"m1","scopeId":"old","text":"老记忆","conversationId":"c1","createdAt":1000}
                ]}""");

        List<MemoryEntry> back = store().list("old");

        assertThat(back).hasSize(1);
        assertThat(back.get(0).pinned()).isFalse();
        assertThat(back.get(0).fromUser()).isFalse();
        assertThat(back.get(0).lastAccessAt()).isEqualTo(1000L);   // 兜成创建时间,不是 0
        assertThat(back.get(0).hasEmbedding()).isFalse();
    }

    @Test
    @DisplayName("touch:召回过的条目记上最后使用时间;刚碰过的不重复写盘")
    void touchUpdatesLastAccessOnce() {
        MemoryStore store = store();
        store.update("scope1", list -> List.of(MemoryEntry.at("m1", "scope1", "老记忆", "c1", 1000L, null)));

        store.touch("scope1", List.of("m1"));
        long touched = store.list("scope1").get(0).lastAccessAt();
        assertThat(touched).isGreaterThan(1000L);

        store.touch("scope1", List.of("m1"));
        assertThat(store.list("scope1").get(0).lastAccessAt()).isEqualTo(touched);
    }
}
