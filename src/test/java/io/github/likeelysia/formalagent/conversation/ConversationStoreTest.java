package io.github.likeelysia.formalagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话落盘:一个会话一个 JSON、一份 index、顺手挡住路径穿越。 */
@DisplayName("ConversationStore 会话落盘")
class ConversationStoreTest {

    @TempDir
    Path tmp;

    private ConversationStore store;

    @BeforeEach
    void setUp() {
        store = new ConversationStore(new ObjectMapper(), tmp);
    }

    @Test
    @DisplayName("ensure:没有就建、有就还你原来那个(反复调用不会多出一份)")
    void ensureIsIdempotent() {
        Conversation first = store.ensure(Conversation.DEFAULT_ID, Conversation.DEFAULT_TITLE);
        Conversation second = store.ensure(Conversation.DEFAULT_ID, "改过的标题");

        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.title()).isEqualTo(Conversation.DEFAULT_TITLE);
        assertThat(store.list()).hasSize(1);
    }

    @Test
    @DisplayName("create:自动生成 id 和文件,列表里能查到")
    void createAddsToIndex() {
        Conversation created = store.create("物理复习");

        assertThat(created.id()).isNotBlank();
        assertThat(created.scopeId()).isEqualTo(created.id());       // 默认:记忆域 = 自己
        assertThat(Files.exists(tmp.resolve(created.id() + ".json"))).isTrue();
        assertThat(store.list()).extracting(ConversationMeta::id).containsExactly(created.id());
        assertThat(store.list().get(0).messageCount()).isZero();
    }

    @Test
    @DisplayName("update:追加消息后能读回来,index 里的条数也跟着变")
    void updateAppendsAndRefreshesIndex() {
        Conversation created = store.create(null);

        store.update(created.id(), c -> c.appended(ChatMessage.user("位移是多少?"))
                .appended(ChatMessage.assistant("零", null)));

        Conversation loaded = store.load(created.id()).orElseThrow();
        assertThat(loaded.messages()).hasSize(2);
        assertThat(loaded.messages().get(0).role()).isEqualTo("user");
        assertThat(loaded.messages().get(1).content()).isEqualTo("零");
        assertThat(store.list().get(0).messageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("update:会话不存在 -> 报错,不会悄悄新建一个")
    void updateUnknownIdFails() {
        assertThatThrownBy(() -> store.update("nope", c -> c))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    @DisplayName("delete:文件和档案一起消失")
    void deleteRemovesFileAndIndexEntry() {
        Conversation created = store.create("待删");
        store.delete(created.id());

        assertThat(Files.exists(tmp.resolve(created.id() + ".json"))).isFalse();
        assertThat(store.list()).isEmpty();
    }

    @Test
    @DisplayName("写出去的 JSON 能严格读回:不会多出 isXxx() 造成的幽灵字段")
    void jsonRoundTripsUnderStrictMapper() throws IOException {
        Conversation created = store.create("t");
        store.update(created.id(), c -> c.appended(ChatMessage.user("hi"))
                .appended(ChatMessage.assistant("yo", null)));

        // 用一个"不认识就报错"(默认行为)的 mapper 回读 —— 多余的属性当场就会暴露
        ObjectMapper strict = new ObjectMapper();
        Conversation back = strict.readValue(Files.readString(tmp.resolve(created.id() + ".json")), Conversation.class);

        assertThat(back.messages()).hasSize(2);
        assertThat(back.messages().get(0).fromUser()).isTrue();
        assertThat(back.messages().get(1).fromAssistant()).isTrue();
    }

    @Test
    @DisplayName("summary / summarizedCount 也能落盘回读")
    void summaryAndWatermarkRoundTrip() {
        Conversation created = store.create("摘要");
        store.update(created.id(), c -> c.appended(ChatMessage.user("q")).withSummary("这是一段摘要", 1));

        Conversation back = store.load(created.id()).orElseThrow();
        assertThat(back.summary()).isEqualTo("这是一段摘要");
        assertThat(back.summarizedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("老档没有 summarizedCount 字段照样读得回,水位当 0")
    void oldFileWithoutWatermarkStillReads() throws IOException {
        Files.writeString(tmp.resolve("old1.json"), """
                {"id":"old1","title":"老会话","scopeId":"old1","createdAt":1,"updatedAt":1,
                 "summary":null,"messages":[]}""");

        Conversation back = store.load("old1").orElseThrow();

        assertThat(back.summarizedCount()).isZero();
        assertThat(back.messages()).isEmpty();
    }

    @Test
    @DisplayName("默认会话不许删")
    void defaultConversationCannotBeDeleted() {
        store.ensure(Conversation.DEFAULT_ID, Conversation.DEFAULT_TITLE);

        assertThatThrownBy(() -> store.delete(Conversation.DEFAULT_ID))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("默认");
    }

    @Test
    @DisplayName("id 是安全边界:../ 这种路径穿越直接被挡")
    void rejectsPathTraversalId() {
        assertThatThrownBy(() -> store.load("../../evil"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("非法");
    }

    @Test
    @DisplayName("index.json 被人删了 -> 扫盘重建,会话不会丢")
    void rebuildsIndexWhenMissing() throws IOException {
        store.create("一");
        store.create("二");
        Files.delete(tmp.resolve("index.json"));

        assertThat(store.list()).hasSize(2);
        assertThat(Files.exists(tmp.resolve("index.json"))).isTrue();
    }

    @Test
    @DisplayName("某个会话文件坏了 -> 列表不崩,坏的那个当不存在")
    void corruptFileDoesNotBreakTheList() throws IOException {
        store.create("好的");
        Files.writeString(tmp.resolve("bad1.json"), "{ 这不是 JSON");

        assertThat(store.list()).extracting(ConversationMeta::title).containsExactly("好的");
        assertThat(store.load("bad1")).isEmpty();
    }

    @Test
    @DisplayName("列表按最近更新排序(刚聊过的排最前)")
    void listIsSortedByUpdatedAt() {
        store.ensure(Conversation.DEFAULT_ID, Conversation.DEFAULT_TITLE);
        Conversation fresh = store.create("刚建的");
        store.update(fresh.id(), c -> c.appended(ChatMessage.user("最新一句话")));

        assertThat(store.list().get(0).id()).isEqualTo(fresh.id());
    }
}
