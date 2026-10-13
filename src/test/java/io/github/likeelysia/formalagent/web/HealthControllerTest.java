package io.github.likeelysia.formalagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.likeelysia.formalagent.knowledge.KnowledgeItem;
import io.github.likeelysia.formalagent.knowledge.KnowledgeStore;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.llm.VisionClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * /api/health:除了"活着",还要能一眼看出"语义检索是不是已经悄悄废了"。
 */
class HealthControllerTest {

    /** 健康检查只用到 all();其余方法给个"不该被调用"的兜底,免得将来误用还以为测过了。 */
    static class StubStore implements KnowledgeStore {
        @Override public void addAll(List<KnowledgeItem> items) { throw new UnsupportedOperationException(); }

        @Override public List<KnowledgeItem> all() {
            return List.of(new KnowledgeItem("k1", "位移", "位置的变化", "大学物理学.pdf", "第 11 页", "concept"));
        }

        @Override public List<KnowledgeItem> search(String query, int topK) { throw new UnsupportedOperationException(); }
        @Override public List<String> sources() { throw new UnsupportedOperationException(); }
        @Override public int removeBySource(String source) { throw new UnsupportedOperationException(); }
        @Override public int replaceSource(String source, List<KnowledgeItem> items) { throw new UnsupportedOperationException(); }
        @Override public Map<String, Integer> countsBySource() { throw new UnsupportedOperationException(); }
    }

    static class StubEmbedding implements EmbeddingClient {
        private final String status;

        StubEmbedding(String status) { this.status = status; }

        @Override public float[] embed(String text) { throw new UnsupportedOperationException(); }
        @Override public String health() { return status; }
        @Override public String modelName() { return "bge-small-zh-v1.5"; }
    }

    /** 视觉桩:健康检查只用这三个默认方法(真调用会抛,免得将来误用还以为测过了) */
    static class StubVision implements VisionClient {
        private final String status;

        StubVision(String status) { this.status = status; }

        @Override public String ask(Path image, String prompt) { throw new UnsupportedOperationException(); }
        @Override public String health() { return status; }
        @Override public String provider() { return "Kimi"; }
        @Override public String modelName() { return "kimi-k2.6"; }
    }

    private static HealthController controller(String embeddingStatus, String visionStatus) {
        return new HealthController(new StubStore(), new StubEmbedding(embeddingStatus), new StubVision(visionStatus));
    }

    @Test
    @DisplayName("embedding 正常:报条数 + status=UP + degraded=false")
    void reportsCountAndHealthyEmbedding() {
        Map<String, Object> body = controller("UP", "UP").health();

        assertThat(body)
                .containsEntry("status", "UP")
                .containsEntry("degraded", false)
                .containsEntry("knowledgeCount", 1);
        assertThat(body.get("embedding")).isEqualTo(Map.of("status", "UP", "model", "bge-small-zh-v1.5"));
    }

    @Test
    @DisplayName("embedding 挂了:服务仍 UP,但 degraded=true 且 embedding.status=DOWN")
    void flagsDegradedWhenEmbeddingIsDown() {
        Map<String, Object> body = controller("DOWN", "UP").health();

        assertThat(body).containsEntry("status", "UP").containsEntry("degraded", true);
        assertThat(body.get("embedding")).isEqualTo(Map.of("status", "DOWN", "model", "bge-small-zh-v1.5"));
    }

    @Test
    @DisplayName("不支持探测(UNKNOWN)也算降级:不能假装它健康")
    void unknownEmbeddingIsAlsoDegraded() {
        Map<String, Object> body = controller("UNKNOWN", "UP").health();

        assertThat(body).containsEntry("degraded", true);
    }

    @Test
    @DisplayName("视觉链路单独报一段:哪家厂商 / 哪个模型 / 配没配好")
    void reportsVisionProviderAndModel() {
        Map<String, Object> body = controller("UP", "UP").health();

        assertThat(body.get("vision"))
                .isEqualTo(Map.of("status", "UP", "provider", "Kimi", "model", "kimi-k2.6"));
    }

    @Test
    @DisplayName("视觉没配好不计入 degraded:它只管图片链路,文字问答不受影响")
    void visionDownDoesNotDegradeTextQa() {
        Map<String, Object> body = controller("UP", "DOWN").health();

        assertThat(body).containsEntry("degraded", false);
        assertThat((Map<String, Object>) body.get("vision")).containsEntry("status", "DOWN");
    }
}
