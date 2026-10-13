package io.github.likeelysia.formalagent.web;

import io.github.likeelysia.formalagent.knowledge.KnowledgeStore;
import io.github.likeelysia.formalagent.llm.EmbeddingClient;
import io.github.likeelysia.formalagent.llm.VisionClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查:GET /api/health —— 给运维/监控用。
 *
 * <p>除了"活着没/知识库多少条",还特意上报<b>两条依赖链路</b>的状态:
 * <ul>
 *   <li><b>embedding</b>:一挂,检索会静默降级成纯关键词(症状只是"换个说法就搜不到"),
 *       所以它直接参与 {@code degraded} 判定 —— {@code degraded=true} 等价于"语义检索已经不工作了"。</li>
 *   <li><b>vision</b>:它只影响图片问答 / 扫描件 OCR,文字问答不受影响,所以<b>不参与 degraded</b>,
 *       单独报一段(哪家厂商 / 哪个模型 / 配没配好)。</li>
 * </ul>
 * 两者都只做「轻量探测」,不真去调一次外部接口。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final KnowledgeStore store;
    private final EmbeddingClient embedding;
    private final VisionClient vision;

    public HealthController(KnowledgeStore store, EmbeddingClient embedding, VisionClient vision) {
        this.store = store;
        this.embedding = embedding;
        this.vision = vision;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String embeddingStatus = embedding.health();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        // 语义检索挂掉 = 服务还活着但"变笨了", 单独给监控一个信号
        body.put("degraded", !"UP".equals(embeddingStatus));
        body.put("knowledgeCount", store.all().size());
        body.put("embedding", Map.of("status", embeddingStatus, "model", embedding.modelName()));

        // 视觉链路:报「哪家 + 哪个模型 + 配没配好」,方便一眼看出换模型有没有换成功
        Map<String, Object> visionInfo = new LinkedHashMap<>();
        visionInfo.put("status", vision.health());
        visionInfo.put("provider", vision.provider());
        visionInfo.put("model", vision.modelName());
        body.put("vision", visionInfo);
        return body;
    }
}
