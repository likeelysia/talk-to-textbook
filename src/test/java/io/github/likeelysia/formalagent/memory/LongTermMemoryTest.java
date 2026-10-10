package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ChatMessage;
import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.conversation.ConversationStore;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 长期记忆:什么时候提炼、提炼了怎么写进去、召回怎么排、出故障怎么退。
 *
 * <p>这里的 Executor 直接给 {@code Runnable::run} —— 提炼变成同步的,断言才写得出来
 * (生产上它是后台线程,这个差别只影响"何时",不影响"结果")。
 */
@DisplayName("LongTermMemory 长期记忆")
class LongTermMemoryTest {

    @TempDir
    Path tmp;

    /** 假提炼器:想回什么回什么,顺便记下被提炼过的那几段。 */
    static class FakeExtractor implements MemoryExtractor {
        /** 想让它提炼出什么,就写什么原文(默认分数 0.5 —— 不钉住)。 */
        List<String> result = List.of();
        /** 当成"用户明确要求记住"的吐出来(keep=1.0 → 入库即钉住)。 */
        boolean mustRemember;
        RuntimeException boom;
        final List<List<ChatMessage>> calls = new ArrayList<>();

        @Override
        public List<MemoryCandidate> extract(List<ChatMessage> turns) {
            calls.add(new ArrayList<>(turns));
            if (boom != null) {
                throw boom;
            }
            double keep = mustRemember ? MemoryCandidate.KEEP_MUST : MemoryCandidate.KEEP_DEFAULT;
            return result.stream().map(text -> new MemoryCandidate(text, keep)).toList();
        }
    }

    /** 假 embedding:按关键词给确定向量,不依赖本机服务。 */
    static class FakeEmbedder implements EmbeddingClient {
        RuntimeException boom;

        @Override
        public float[] embed(String text) {
            if (boom != null) {
                throw boom;
            }
            return vector(text);
        }

        static float[] vector(String text) {
            float[] v = new float[4];
            if (text.contains("物理")) v[0] = 1;
            if (text.contains("Java")) v[1] = 1;
            if (text.contains("出处") || text.contains("偏好")) v[2] = 1;
            if (v[0] == 0 && v[1] == 0 && v[2] == 0) v[3] = 1;   // 都不是:给它自己一个方向(和别人不相似)
            return v;
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    private ConversationService conversations;
    private MemoryStore store;
    private FakeExtractor extractor;
    private FakeEmbedder embedder;

    @BeforeEach
    void setUp() {
        conversations = new ConversationService(
                new ConversationStore(new ObjectMapper(), tmp.resolve("convs")));
        conversations.init();
        store = new MemoryStore(new ObjectMapper(), tmp.resolve("mem"), "fake");
        extractor = new FakeExtractor();
        embedder = new FakeEmbedder();
    }

    private LongTermMemory memory(boolean enabled, int everyTurns) {
        return memory(enabled, everyTurns, 500);
    }

    private LongTermMemory memory(boolean enabled, int everyTurns, int maxStored) {
        AppConfig.Memory.LongTerm config = new AppConfig.Memory.LongTerm(
                enabled, tmp.resolve("mem").toString(), everyTurns, 0.5, 5, 0.9, 5, 20, maxStored);
        return new LongTermMemory(store, extractor, embedder, conversations, config, Runnable::run);
    }

    /** 建一个会话,塞进去 n 轮「提问 + 回答」。 */
    private String conversationWithTurns(int turns) {
        String id = conversations.create("测试会话").id();
        for (int i = 1; i <= turns; i++) {
            conversations.record(id, "第 " + i + " 个问题,关于物理的", "第 " + i + " 个回答", null);
        }
        return id;
    }

    private static List<String> texts(List<MemoryEntry> entries) {
        return entries.stream().map(MemoryEntry::text).toList();
    }

    @Test
    @DisplayName("没攒够轮数不提炼;够了一次提炼")
    void extractsOnlyAfterEnoughTurns() {
        LongTermMemory memory = memory(true, 3);
        String id = conversationWithTurns(2);

        memory.maybeExtract(conversations.get(id));
        assertThat(extractor.calls).isEmpty();

        conversations.record(id, "第三轮的问题,关于物理的", "第三轮的回答", null);
        memory.maybeExtract(conversations.get(id));

        assertThat(extractor.calls).hasSize(1);
        assertThat(extractor.calls.get(0)).hasSize(6);
    }

    @Test
    @DisplayName("提炼完水位推进;下次只把新增的那一段送去提炼")
    void watermarkAdvancesAndOnlyNewTurnsAreSent() {
        LongTermMemory memory = memory(true, 2);
        String id = conversationWithTurns(2);

        memory.maybeExtract(conversations.get(id));
        assertThat(conversations.get(id).memoryCount()).isEqualTo(4);      // 2 轮 = 4 条消息

        conversations.record(id, "第四轮的问题,关于物理的", "第四轮的回答", null);
        memory.maybeExtract(conversations.get(id));                        // 只有 1 轮,还不够
        assertThat(extractor.calls).hasSize(1);

        conversations.record(id, "第五轮的问题,关于物理的", "第五轮的回答", null);
        memory.maybeExtract(conversations.get(id));

        assertThat(extractor.calls).hasSize(2);
        assertThat(extractor.calls.get(1)).extracting(ChatMessage::content)
                .contains("第四轮的问题,关于物理的")
                .doesNotContain("第 1 个问题,关于物理的");
    }

    @Test
    @DisplayName("提炼出来的条目落进这个会话的记忆域")
    void storesIntoTheScope() {
        LongTermMemory memory = memory(true, 1);
        extractor.result = List.of("用户在学物理", "用户偏好给出处");
        String id = conversationWithTurns(1);

        memory.maybeExtract(conversations.get(id));

        Conversation conv = conversations.get(id);
        assertThat(texts(store.list(conv.scopeId()))).containsExactly("用户在学物理", "用户偏好给出处");
    }

    @Test
    @DisplayName("意思一样的两条合并,而不是堆两条")
    void similarMemoriesAreMerged() {
        LongTermMemory memory = memory(true, 1);
        String id = conversationWithTurns(1);
        extractor.result = List.of("用户在学物理");
        memory.maybeExtract(conversations.get(id));

        extractor.result = List.of("用户在学物理(正在复习)");
        conversations.record(id, "第二轮的问题,关于物理的", "第二轮的回答", null);
        memory.maybeExtract(conversations.get(id));

        assertThat(store.list(conversations.get(id).scopeId())).hasSize(1);
        assertThat(texts(store.list(conversations.get(id).scopeId()))).containsExactly("用户在学物理(正在复习)");
    }

    @Test
    @DisplayName("embedding 服务没开:记忆照样入库,只是没向量")
    void storesWithoutVectorsWhenEmbeddingIsDown() {
        embedder.boom = new IllegalStateException("连不上 8090");
        LongTermMemory memory = memory(true, 1);
        extractor.result = List.of("用户在学物理");
        String id = conversationWithTurns(1);

        memory.maybeExtract(conversations.get(id));

        List<MemoryEntry> saved = store.list(conversations.get(id).scopeId());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).hasEmbedding()).isFalse();
    }

    @Test
    @DisplayName("召回:按相似度取最相关的几条")
    void recallPicksTheMostSimilar() {
        LongTermMemory memory = memory(true, 1);
        store.update("scope1", list -> List.of(
                MemoryEntry.of("scope1", "用户在学物理", "c1", FakeEmbedder.vector("物理")),
                MemoryEntry.of("scope1", "用户在学 Java", "c1", FakeEmbedder.vector("Java"))));

        assertThat(texts(memory.recall("scope1", "物理题怎么做", 1))).containsExactly("用户在学物理");
        assertThat(texts(memory.recall("scope1", "Java 面试考什么", 1))).containsExactly("用户在学 Java");
    }

    @Test
    @DisplayName("embedding 不可用:召回退化成按时间取最近几条")
    void recallFallsBackToRecency() {
        LongTermMemory memory = memory(true, 1);
        store.update("scope1", list -> List.of(
                MemoryEntry.at("m1", "scope1", "旧的记忆", "c1", 1000L, null),
                MemoryEntry.at("m2", "scope1", "新的记忆", "c1", 2000L, null)));
        embedder.boom = new IllegalStateException("连不上 8090");

        assertThat(texts(memory.recall("scope1", "随便问点什么", 1))).containsExactly("新的记忆");
    }

    @Test
    @DisplayName("embedding 不可用:提问里命中的记忆排前面(不会被新记的挤掉)")
    void recallFallbackPrefersLiteralHit() {
        LongTermMemory memory = memory(true, 1);
        store.update("scope1", list -> List.of(
                MemoryEntry.at("m1", "scope1", "用户要求回答给出处", "c1", 1000L, null),
                MemoryEntry.at("m2", "scope1", "用户在学物理", "c1", 2000L, null)));
        embedder.boom = new IllegalStateException("连不上 8090");

        // 命中的是那条旧的 → 它得顶到前面(纯按时间会把它埋了)
        assertThat(texts(memory.recall("scope1", "回答要不要给出处?", 1))).containsExactly("用户要求回答给出处");
        // 一条都不命中 → 退回按时间(行为跟以前一模一样)
        assertThat(texts(memory.recall("scope1", "最近在忙什么", 1))).containsExactly("用户在学物理");
    }

    @Test
    @DisplayName("字面命中分:问句里的内容命中越多分越高,完全无关是 0")
    void keywordScoreBasics() {
        assertThat(LongTermMemory.keywordScore("回答要不要给出处?", "用户要求回答给出处")).isGreaterThan(0.3);
        assertThat(LongTermMemory.keywordScore("物理怎么学", "用户在学物理")).isGreaterThan(0);
        assertThat(LongTermMemory.keywordScore("最近在忙什么", "用户要求回答给出处")).isZero();
        assertThat(LongTermMemory.keywordScore("", "用户要求回答给出处")).isZero();
    }

    @Test
    @DisplayName("召回拼成一条 system 消息;没有记忆就没有这条消息")
    void recallAsSystemMessage() {
        LongTermMemory memory = memory(true, 1);
        assertThat(memory.recallAsMessage("scope1", "随便问问")).isNull();

        store.update("scope1", list -> List.of(MemoryEntry.of("scope1", "用户偏好给出处", "c1", null)));
        AgentMessage message = memory.recallAsMessage("scope1", "随便问问");

        assertThat(message.role()).isEqualTo("system");
        assertThat(message.content()).contains("长期记忆").contains("- 用户偏好给出处")
                .contains("不要编造用户说过的话");      // 防编造那句也得注入进去
    }

    @Test
    @DisplayName("关掉开关:既不提炼也不召回")
    void disabledDoesNothing() {
        LongTermMemory memory = memory(false, 1);
        extractor.result = List.of("用户在学物理");
        String id = conversationWithTurns(1);

        memory.maybeExtract(conversations.get(id));
        store.update("scope1", list -> List.of(MemoryEntry.of("scope1", "用户在学物理", "c1", null)));

        assertThat(extractor.calls).isEmpty();
        assertThat(store.list(conversations.get(id).scopeId())).isEmpty();
        assertThat(memory.recall("scope1", "物理")).isEmpty();
        assertThat(memory.recallAsMessage("scope1", "物理")).isNull();
    }

    @Test
    @DisplayName("提炼失败也要把水位推走(不然每轮都对着同一段重试)")
    void failedExtractionStillAdvancesWatermark() {
        LongTermMemory memory = memory(true, 1);
        extractor.boom = new IllegalStateException("提炼模型超时");
        String id = conversationWithTurns(1);

        memory.maybeExtract(conversations.get(id));

        assertThat(conversations.get(id).memoryCount()).isEqualTo(2);
        assertThat(store.list(conversations.get(id).scopeId())).isEmpty();
    }

    // ==================== 治理(E 段):上限 / 钉住 / 看得见改得动 ====================

    @Test
    @DisplayName("用户明确要求记住的(keep=1.0):入库就直接钉住、算“用户亲口说的”")
    void userRequestedFactsArePinned() {
        LongTermMemory memory = memory(true, 1);
        extractor.result = List.of("用户叫鹏哥");
        extractor.mustRemember = true;
        String id = conversationWithTurns(1);

        memory.maybeExtract(conversations.get(id));

        List<MemoryEntry> saved = store.list(conversations.get(id).scopeId());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).pinned()).isTrue();
        assertThat(saved.get(0).fromUser()).isTrue();
    }

    @Test
    @DisplayName("记忆域满了:淘汰最久没被用到的,钉住的一条都不动")
    void trimsOldestUnusedButKeepsPinned() {
        LongTermMemory memory = memory(true, 1, 3);
        store.update("scope1", list -> List.of(
                MemoryEntry.at("m1", "scope1", "用户住在一楼", "c1", 1000L, null),
                MemoryEntry.at("m2", "scope1", "用户在学物理", "c1", 2000L, null).withPinned(true),
                MemoryEntry.at("m3", "scope1", "用户喜欢喝咖啡", "c1", 3000L, null)));

        memory.storeMemories("scope1", List.of(MemoryCandidate.of("用户有一条狗")), "c1");

        // 进了 1 条 → 4 条 > 上限 3 → 挤掉最久没被用到的那条(最早、又没钉住的那条)
        assertThat(texts(store.list("scope1")))
                .containsExactly("用户在学物理", "用户喜欢喝咖啡", "用户有一条狗");
    }

    @Test
    @DisplayName("治理:手动加(直接钉住)/ 改文字 / 钉与取消 / 删一条 / 清空")
    void manualGovernanceCoversAddEditDeleteClear() {
        LongTermMemory memory = memory(true, 1);
        String id = conversationWithTurns(1);

        MemoryEntry added = memory.remember(id, "用户叫鹏哥");
        assertThat(added.pinned()).isTrue();
        assertThat(added.fromUser()).isTrue();
        assertThat(texts(memory.list(id))).containsExactly("用户叫鹏哥");

        assertThat(memory.edit(id, added.id(), "用户叫鹏哥,在准备 Java 实习", true).text())
                .isEqualTo("用户叫鹏哥,在准备 Java 实习");
        assertThat(memory.edit(id, added.id(), null, false).pinned()).isFalse();

        assertThat(memory.forget(id, added.id())).isTrue();
        assertThat(memory.forget(id, added.id())).isFalse();     // 已经没了
        assertThat(memory.list(id)).isEmpty();

        memory.remember(id, "再记一条");
        memory.forgetAll(id);
        assertThat(memory.list(id)).isEmpty();

        assertThatThrownBy(() -> memory.remember(id, "   "))
                .isInstanceOf(AgentException.class);
    }

    @Test
    @DisplayName("召回过的记忆会被记上“最后使用时间”(淘汰排序靠它)")
    void recallTouchesLastAccess() {
        LongTermMemory memory = memory(true, 1);
        store.update("scope1", list -> List.of(
                MemoryEntry.at("m1", "scope1", "用户在学物理", "c1", 1000L, FakeEmbedder.vector("物理"))));

        memory.recall("scope1", "物理怎么学", 1);

        assertThat(store.list("scope1").get(0).lastAccessAt()).isGreaterThan(1000L);
    }
}
