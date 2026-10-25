package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.knowledge.JsonKnowledgeStore;
import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
}
