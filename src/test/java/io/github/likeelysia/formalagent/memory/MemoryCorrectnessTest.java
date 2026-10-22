package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.conversation.ChatMessage;
import io.github.likeelysia.formalagent.conversation.Conversation;
import io.github.likeelysia.formalagent.conversation.ConversationService;
import io.github.likeelysia.formalagent.conversation.ConversationStore;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 这一轮修正的回归测试:域、代次、向量、水位、钉住、降级。 */
@DisplayName("记忆正确性回归")
class MemoryCorrectnessTest {

    @TempDir
    Path tmp;

    private ConversationService conversations;
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        conversations = new ConversationService(new ConversationStore(new ObjectMapper(), tmp.resolve("convs")));
        conversations.init();
        store = new MemoryStore(new ObjectMapper(), tmp.resolve("mem"), "test");
    }

    static class StubExtractor implements MemoryExtractor {
        Result result = Result.empty();
        RuntimeException boom;
        int calls;

        @Override
        public Result extract(List<ChatMessage> turns) {
            calls++;
            if (boom != null) {
                throw boom;
            }
            return result;
        }
    }

    private static EmbeddingClient fixedVector(float... vector) {
        return text -> vector.clone();
    }

    private static final MemoryReconciler NOOP = (candidates, neighbors) -> Map.of();

    private LongTermMemory memory(MemoryExtractor extractor, EmbeddingClient embedder,
                                  MemoryReconciler reconciler, int maxStored, Executor executor) {
        return memory(extractor, embedder, reconciler, maxStored, executor, null);
    }

    private LongTermMemory memory(MemoryExtractor extractor, EmbeddingClient embedder,
                                  MemoryReconciler reconciler, int maxStored, Executor executor,
                                  MemoryConflictStore conflicts) {
        AppConfig.Memory.LongTerm config = new AppConfig.Memory.LongTerm(
                true, tmp.resolve("mem").toString(), 1, 0.5, 5, 0.9, 5, 0, maxStored);
        return new LongTermMemory(store, extractor, embedder, conversations, config, executor,
                reconciler, conflicts);
    }

    private String conversationWithTurns(int turns) {
        String id = conversations.create("测试会话").id();
        for (int i = 1; i <= turns; i++) {
            conversations.record(id, "第 " + i + " 轮问题,关于物理的", "回答", null);
        }
        return id;
    }

    @Test
    @DisplayName("链式共享 A→B→C:列表、删除、提炼都落到同一个根域")
    void chainShareUsesOneFinalScope() {
        String a = conversationWithTurns(1);
        String b = conversations.create("B").id();
        String c = conversations.create("C").id();
        conversations.shareMemory(b, a);
        conversations.shareMemory(c, b);

        LongTermMemory memory = memory(new StubExtractor(), fixedVector(1, 0), NOOP, 500, Runnable::run);

        MemoryEntry entry = memory.remember(a, "用户叫鹏哥");

        assertThat(memory.list(c)).extracting(MemoryEntry::text).containsExactly("用户叫鹏哥");
        assertThat(entry.scopeId()).isEqualTo(a);

        assertThat(memory.forget(c, entry.id())).isTrue();
        assertThat(memory.list(a)).isEmpty();
    }

    @Test
    @DisplayName("提炼排队期间把 A 从共享里拆出去:旧任务放弃,不写进任何域")
    void queuedTaskAbortsWhenScopeChanges() {
        String a = conversationWithTurns(1);
        String b = conversations.create("B").id();
        StubExtractor extractor = new StubExtractor();
        extractor.result = MemoryExtractor.Result.ok(List.of(MemoryCandidate.of("用户在学物理")));
        Deque<Runnable> queued = new ArrayDeque<>();
        LongTermMemory memory = memory(extractor, fixedVector(1, 0), NOOP, 500, queued::add);

        memory.maybeExtract(conversations.get(a));       // 捕获的是域 A
        conversations.shareMemory(a, b);                 // A 改指向 B
        queued.poll().run();                             // 旧任务现在才跑

        assertThat(store.list(a)).isEmpty();
        assertThat(store.list(b)).isEmpty();
        assertThat(conversations.get(a).memoryCount()).isEqualTo(2);   // 旧消息按已放弃处理,不迁移到新域
    }

    @Test
    @DisplayName("提炼排队期间清空记忆:旧任务完成后仍然为空,且水位照推")
    void clearDuringQueuedTaskDoesNotRestore() {
        String a = conversationWithTurns(1);
        StubExtractor extractor = new StubExtractor();
        extractor.result = MemoryExtractor.Result.ok(List.of(MemoryCandidate.of("用户在学物理")));
        Deque<Runnable> queued = new ArrayDeque<>();
        LongTermMemory memory = memory(extractor, fixedVector(1, 0), NOOP, 500, queued::add);

        memory.maybeExtract(conversations.get(a));
        memory.forgetAll(a);                              // 代次 +1
        queued.poll().run();                              // 旧代次任务

        assertThat(memory.list(a)).isEmpty();
        assertThat(store.generation(a)).isEqualTo(1);
        assertThat(conversations.get(a).memoryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("单条删除后旧任务不能把它复活;用户明确要求重新记才可以")
    void singleDeleteTombstoneBlocksRestore() {
        String a = conversationWithTurns(1);
        LongTermMemory memory = memory(new StubExtractor(), fixedVector(1, 0), NOOP, 500, Runnable::run);

        MemoryEntry entry = memory.remember(a, "用户在学物理");
        assertThat(memory.forget(a, entry.id())).isTrue();

        memory.storeMemories(a, List.of(MemoryCandidate.of("用户在学物理")), a);
        assertThat(memory.list(a)).isEmpty();

        memory.storeMemories(a, List.of(MemoryCandidate.remember("用户在学物理")), a);
        assertThat(memory.list(a)).extracting(MemoryEntry::text).containsExactly("用户在学物理");
    }

    @Test
    @DisplayName("UPDATE 的向量由最终保存文本生成,不是候选文本的向量")
    void updateVectorMatchesFinalText() {
        String a = conversationWithTurns(1);
        EmbeddingClient embedder = text -> text.contains("候选") ? new float[] {1, 0}
                : new float[] {0, 1};
        store.update(a, entries -> List.of(new MemoryEntry("m1", a, "旧事实", a, 1000L, 0L,
                false, MemoryEntry.ORIGIN_AUTO, new float[] {1, 0}, 0L,
                MemoryEntry.STATUS_ACTIVE, List.of())));
        MemoryReconciler update = (candidates, neighbors) -> Map.of(
                0, new MemoryReconciler.Decision(MemoryReconciler.UPDATE, "m1", "最终文本"));
        LongTermMemory memory = memory(new StubExtractor(), embedder, update, 500, Runnable::run);

        memory.storeMemories(a, List.of(new MemoryCandidate("候选文本", 0.9)), a);

        MemoryEntry latest = memory.list(a).get(0);
        assertThat(latest.text()).isEqualTo("最终文本");
        assertThat(latest.embedding()).containsExactly(0f, 1f);
    }

    @Test
    @DisplayName("提炼失败保留水位;换新实例后能补提炼")
    void failedExtractionCanRecover() {
        String a = conversationWithTurns(1);
        StubExtractor extractor = new StubExtractor();
        extractor.result = MemoryExtractor.Result.failed();

        LongTermMemory first = memory(extractor, fixedVector(1, 0), NOOP, 500, Runnable::run);
        first.maybeExtract(conversations.get(a));
        assertThat(conversations.get(a).memoryCount()).isZero();
        assertThat(first.list(a)).isEmpty();

        extractor.result = MemoryExtractor.Result.ok(List.of(MemoryCandidate.of("用户在学物理")));
        LongTermMemory second = memory(extractor, fixedVector(1, 0), NOOP, 500, Runnable::run);
        second.maybeExtract(conversations.get(a));

        assertThat(conversations.get(a).memoryCount()).isEqualTo(2);
        assertThat(second.list(a)).extracting(MemoryEntry::text).containsExactly("用户在学物理");
    }

    @Test
    @DisplayName("同一批消息重复执行:批次幂等,不重复写入")
    void sameBatchIsIdempotent() {
        String a = conversationWithTurns(1);
        LongTermMemory memory = memory(new StubExtractor(), fixedVector(1, 0), NOOP, 500, Runnable::run);
        long generation = store.generation(a);

        MemoryCorrectnessTestAccess first = new MemoryCorrectnessTestAccess(memory,
                List.of(MemoryCandidate.of("用户在学物理")), a, generation, "batch-1");
        MemoryCorrectnessTestAccess second = new MemoryCorrectnessTestAccess(memory,
                List.of(MemoryCandidate.of("用户在学物理")), a, generation, "batch-1");

        assertThat(first.outcome().duplicate()).isFalse();
        assertThat(second.outcome().duplicate()).isTrue();
        assertThat(memory.list(a)).hasSize(1);
    }

    /** 小包装:把包可见的 storeMemories 暴露成测试可读的 outcome。 */
    private record MemoryCorrectnessTestAccess(LongTermMemory memory, List<MemoryCandidate> candidates,
                                               String conversationId, long generation, String batchKey) {
        LongTermMemory.StoreOutcome outcome() {
            return memory.storeMemories(conversationId, candidates, conversationId, generation, batchKey);
        }
    }

    @Test
    @DisplayName("NOOP + 用户明确要求记住:已有条目补上钉住")
    void noopWithExplicitRememberPinsExisting() {
        String a = conversationWithTurns(1);
        store.update(a, entries -> List.of(MemoryEntry.of(a, "用户叫鹏哥", a, new float[] {1, 0})));
        MemoryReconciler noop = (candidates, neighbors) -> Map.of(
                0, new MemoryReconciler.Decision(MemoryReconciler.NOOP, null, null));
        LongTermMemory memory = memory(new StubExtractor(), fixedVector(1, 0), noop, 500, Runnable::run);

        memory.storeMemories(a, List.of(new MemoryCandidate("用户叫鹏哥", 0.6, true, false)), a);

        assertThat(memory.list(a)).hasSize(1);
        assertThat(memory.list(a).get(0).pinned()).isTrue();
    }

    @Test
    @DisplayName("用户明确说忘掉:即使条目钉住也能软删")
    void explicitForgetCanDeletePinned() {
        String a = conversationWithTurns(1);
        store.update(a, entries -> List.of(MemoryEntry.of(a, "用户养了一只猫", a, new float[] {1, 0})
                .withPinned(true)));
        MemoryReconciler noop = (candidates, neighbors) -> Map.of(
                0, new MemoryReconciler.Decision(MemoryReconciler.NOOP, null, null));
        LongTermMemory memory = memory(new StubExtractor(), fixedVector(1, 0), noop, 500, Runnable::run);

        memory.storeMemories(a, List.of(MemoryCandidate.forget("用户说别再提猫了")), a);

        assertThat(memory.list(a)).isEmpty();
        assertThat(store.list(a)).anyMatch(e -> MemoryEntry.STATUS_DELETED.equals(e.status()));
    }

    @Test
    @DisplayName("与核心档案冲突:只记录待确认,候选仍作为长期记忆保存,核心档案不动")
    void coreConflictIsRecordedNotApplied() {
        String a = conversationWithTurns(1);
        MemoryConflictStore conflicts = new MemoryConflictStore(
                new ObjectMapper(), tmp.resolve("conflicts.json"));
        MemoryReconciler coreConflict = (candidates, neighbors) -> Map.of(
                0, new MemoryReconciler.Decision(MemoryReconciler.CORE_CONFLICT, null, null));
        LongTermMemory memory = memory(new StubExtractor(), fixedVector(1, 0), coreConflict, 500,
                Runnable::run, conflicts);

        memory.storeMemories(a, List.of(MemoryCandidate.of("用户要就业")), a);

        assertThat(conflicts.list()).hasSize(1);
        assertThat(conflicts.list().get(0).candidateText()).isEqualTo("用户要就业");
        assertThat(memory.list(a)).extracting(MemoryEntry::text).containsExactly("用户要就业");
    }

    @Test
    @DisplayName("钉住条目超过容量:保留全部并报告,不暗中删除")
    void pinnedOverLimitIsKept() {
        String a = conversationWithTurns(1);
        store.update(a, entries -> List.of(
                MemoryEntry.of(a, "钉住一", a, new float[] {1, 0}).withPinned(true),
                MemoryEntry.of(a, "钉住二", a, new float[] {1, 0}).withPinned(true)));
        LongTermMemory memory = memory(new StubExtractor(), fixedVector(1, 0), NOOP, 1, Runnable::run);

        memory.storeMemories(a, List.of(MemoryCandidate.of("新事实")), a);

        assertThat(memory.list(a)).extracting(MemoryEntry::text)
                .containsExactlyInAnyOrder("钉住一", "钉住二", "新事实");
    }
}
