package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.knowledge.JsonKnowledgeStore;
import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class KnowledgeControllerTest {

    @TempDir Path tmp;
    private JsonKnowledgeStore store;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        store = new JsonKnowledgeStore(new ObjectMapper(),
                text -> new float[]{1f, 0f}, tmp.resolve("base.json").toString(), 0);
        controller = new KnowledgeController(store);
    }

    private static KnowledgeItem item(String id, String name, String source) {
        return new KnowledgeItem(id, name, "detail-" + name, source, "第 1 页", "pdf");
    }

    @Test
    @DisplayName("count/page/get 都走轻量接口")
    void countPageAndGet() {
        store.addAll(List.of(item("a", "A", "bookA.pdf"), item("b", "B", "bookA.pdf"),
                item("c", "C", "bookB.pdf")));

        assertThat(controller.count()).containsEntry("count", 3);

        Map<String, Object> page = controller.page(1, 1);
        assertThat(page).containsEntry("count", 3).containsEntry("offset", 1);
        assertThat(page.get("items")).asList().hasSize(1);

        assertThat(controller.item("b").name()).isEqualTo("B");
        assertThatThrownBy(() -> controller.item("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("向量补齐是显式分批接口,状态可查")
    void vectorBackfillIsExplicitAndResumable() {
        store.addAll(List.of(item("a", "A", "bookA.pdf"), item("b", "B", "bookA.pdf"),
                item("c", "C", "bookB.pdf")));

        assertThat(controller.vectorStatus()).containsEntry("missing", 3)
                .containsEntry("complete", false);

        assertThat(controller.backfillVectors(2).remaining()).isEqualTo(1);
        assertThat(controller.backfillVectors(2).complete()).isTrue();
        assertThat(controller.vectorStatus()).containsEntry("missing", 0)
                .containsEntry("complete", true);
    }

    @Test
    @DisplayName("补齐重叠时返回 409,不重复进入 embedding")
    void concurrentBackfillReturnsConflict() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EmbeddingClient slow = new EmbeddingClient() {
            @Override public float[] embed(String text) { return new float[]{1f, 0f}; }
            @Override public List<float[]> embedAll(List<String> texts) {
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return texts.stream().map(t -> new float[]{1f, 0f}).toList();
            }
        };
        JsonKnowledgeStore slowStore = new JsonKnowledgeStore(new ObjectMapper(), slow,
                tmp.resolve("busy.json").toString(), 0);
        slowStore.addAll(List.of(item("a", "A", "bookA.pdf")));
        KnowledgeController busyController = new KnowledgeController(slowStore);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> busyController.backfillVectors(1));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> busyController.backfillVectors(1))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
