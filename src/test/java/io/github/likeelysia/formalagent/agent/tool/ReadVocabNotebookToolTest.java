package io.github.likeelysia.formalagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.vocab.VocabNotebook;
import io.github.likeelysia.formalagent.vocab.VocabService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadVocabNotebookToolTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void readsReviewQueueAndPlanDayButNeverWrites() throws Exception {
        var notebook = new VocabNotebook(mapper, temp.resolve("notebook.json").toString());
        var word = new VocabService.Word("bank", "", "银行", "a place for money", "", "",
                List.of("bank"), 1, List.of("I keep money in the bank."), "", "bank", "FOUND", null);
        var entry = notebook.save(List.of(word)).entries().get(0);
        notebook.createPlan(30);
        notebook.queueForReview(List.of(entry.id()), 5);
        var before = notebook.list().entries().get(0);

        var tool = new ReadVocabNotebookTool(notebook, mapper);
        JsonNode review = mapper.readTree(tool.execute(Map.of("scope", "review", "day", 5)));
        assertThat(review.path("total_review").asInt()).isEqualTo(1);
        assertThat(review.path("plan_days").asInt()).isEqualTo(30);
        assertThat(review.path("words").get(0).path("plan_day").asInt()).isEqualTo(5);
        assertThat(review.path("words").get(0).path("in_review").asBoolean()).isTrue();
        assertThat(notebook.list().entries().get(0)).isEqualTo(before);
    }

    @Test void dayRequiresReviewScope() {
        var notebook = new VocabNotebook(mapper, temp.resolve("notebook.json").toString());
        var tool = new ReadVocabNotebookTool(notebook, mapper);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.execute(Map.of("scope", "all", "day", 1)))
                .hasMessageContaining("review");
    }
}
