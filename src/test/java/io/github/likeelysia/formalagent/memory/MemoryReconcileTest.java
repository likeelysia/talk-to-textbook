package io.github.likeelysia.formalagent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 记忆对账:UPDATE 软覆盖、DELETE 保护钉住、对账失败退回旧合并逻辑。 */
class MemoryReconcileTest {

    @TempDir
    Path dir;

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(new ObjectMapper(), dir, "test");
    }

    private LongTermMemory memory(MemoryReconciler reconciler) {
        return new LongTermMemory(store, turns -> MemoryExtractor.Result.empty(),
                text -> new float[] {1, 0}, null, null, Runnable::run, reconciler);
    }

    private void seed(String id, String text, boolean pinned) {
        store.update("s", entries -> List.of(new MemoryEntry(id, "s", text, "c1", 1000L, 0L,
                pinned, MemoryEntry.ORIGIN_USER, new float[] {1, 0}, 0L,
                MemoryEntry.STATUS_ACTIVE, List.of())));
    }

    @Test
    void updateSoftSupersedesTheOldEntry() {
        seed("m1", "用户想考研", true);
        MemoryReconciler reconciler = (candidates, neighbors) -> Map.of(
                0, new MemoryReconciler.Decision(MemoryReconciler.UPDATE, "m1", "用户要改成就业"));

        long[] counts = memory(reconciler).storeMemories("s",
                List.of(new MemoryCandidate("用户要改成就业", 0.9)), "c1");

        assertThat(counts[1]).isEqualTo(1);
        List<MemoryEntry> all = store.list("s");
        assertThat(all).anyMatch(e -> e.id().equals("m1")
                && MemoryEntry.STATUS_SUPERSEDED.equals(e.status()));
        assertThat(all.stream().filter(MemoryEntry::active))
                .anyMatch(e -> e.text().contains("就业") && e.supersedes().contains("m1"));
    }

    @Test
    void deleteNeverRemovesPinnedMemory() {
        seed("m1", "用户特意让记住的事", true);
        MemoryReconciler reconciler = (candidates, neighbors) -> Map.of(
                0, new MemoryReconciler.Decision(MemoryReconciler.DELETE, "m1", null));

        long[] counts = memory(reconciler).storeMemories("s",
                List.of(new MemoryCandidate("用户说忘掉这件事", 0.9)), "c1");

        assertThat(counts[2]).isZero();
        assertThat(store.list("s")).anyMatch(e -> e.id().equals("m1") && e.active());
    }

    @Test
    void deleteSoftDeletesNormalMemory() {
        seed("m1", "用户养了一只猫", false);
        MemoryReconciler reconciler = (candidates, neighbors) -> Map.of(
                0, new MemoryReconciler.Decision(MemoryReconciler.DELETE, "m1", null));

        long[] counts = memory(reconciler).storeMemories("s",
                List.of(new MemoryCandidate("用户说别再提猫了", 0.9)), "c1");

        assertThat(counts[2]).isEqualTo(1);
        assertThat(store.list("s")).anyMatch(e -> e.id().equals("m1")
                && MemoryEntry.STATUS_DELETED.equals(e.status()));
    }

    @Test
    void exactDuplicateIsSkippedWhenReconcilerSaysNothing() {
        seed("m1", "用户想考研", false);
        MemoryReconciler noop = (candidates, neighbors) -> Map.of();

        long[] counts = memory(noop).storeMemories("s",
                List.of(new MemoryCandidate("用户想考研", 0.8)), "c1");

        assertThat(counts[0]).isZero();                     // 完全重复:不新增
        assertThat(counts[1]).isZero();                     // 也不按相似度覆盖
        assertThat(store.list("s").stream().filter(MemoryEntry::active)).hasSize(1);
        assertThat(store.list("s").stream().filter(MemoryEntry::active).findFirst().orElseThrow().text())
                .isEqualTo("用户想考研");
    }
}
