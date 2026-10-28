package io.github.likeelysia.formalagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.llm.LocalEmbeddingClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 现场验收的小入口：复用真实客户端和检索器，保存指定查询的 top-K。
 * 不扫描整个应用、不启动 Web、不接触账号数据；仅对明确指定的隔离知识文件运行。
 * 凭据由进程环境注入，参数中只放知识路径、问题、结果路径。
 */
public final class KnowledgeSearchProbe {
    private KnowledgeSearchProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("知识文件、查询、结果文件必填");
        String url = System.getenv("FA_EMBEDDING_URL");
        String model = System.getenv("FA_EMBEDDING_MODEL");
        if (url == null || model == null) throw new IllegalArgumentException("必须显式配置 embedding");
        AppConfig config = new AppConfig(false, new AppConfig.Llm(30, 5, 1, 100, 100),
                null, null, new AppConfig.Embedding(url, model), null, null, null);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // 普通 Spring 小容器不加载 application.yml，显式沿用其中的路径变量映射。
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("probe-path", Map.of(
                            "fa.embedding.embeddings-path",
                            System.getenv().getOrDefault("FA_EMBEDDING_PATH", "/v1/embeddings"))));
            ObjectMapper mapper = new ObjectMapper();
            context.registerBean(AppConfig.class, () -> config);
            context.registerBean(ObjectMapper.class, () -> mapper);
            context.register(LocalEmbeddingClient.class);
            context.refresh();
            JsonKnowledgeStore store = new JsonKnowledgeStore(mapper,
                    context.getBean(LocalEmbeddingClient.class), args[0],
                    Double.parseDouble(System.getenv().getOrDefault("FA_KNOWLEDGE_MIN_SCORE", "0.45")));
            Object result;
            if (args[1].startsWith("@")) {
                // 批量评测只加载一次库；沿用既有 golden set 的名称/页码口径。
                List<Map<String, Object>> cases = mapper.readValue(Path.of(args[1].substring(1)).toFile(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() { });
                List<Map<String, Object>> rows = new ArrayList<>();
                int answerable = 0, matched = 0, pageHit = 0, unanswerable = 0, refused = 0;
                double reciprocal = 0;
                for (var one : cases) {
                    String query = (String) one.get("query"), expected = (String) one.get("expectName");
                    long started = System.nanoTime();
                    var hits = store.search(query, 5);
                    Map<String, Object> row = new LinkedHashMap<>(one);
                    row.put("hits", hits); row.put("milliseconds", (System.nanoTime() - started) / 1_000_000);
                    rows.add(row);
                    if (expected == null || expected.isBlank()) {
                        unanswerable++; if (hits.isEmpty()) refused++;
                    } else {
                        answerable++;
                        for (int i = 0; i < hits.size(); i++) {
                            if (expected.equals(hits.get(i).name())) { matched++; reciprocal += 1.0 / (i + 1); break; }
                        }
                        if (hits.stream().anyMatch(i -> java.util.Objects.equals(one.get("expectPage"), i.location()))) pageHit++;
                    }
                }
                result = Map.of("knowledgeCount", store.count(), "missingVectors", store.missingVectorCount(),
                        "answerable", answerable, "matched", matched, "recallAt5", answerable == 0 ? 0 : (double) matched / answerable,
                        "mrr", answerable == 0 ? 0 : reciprocal / answerable,
                        "pageHitAt5", answerable == 0 ? 0 : (double) pageHit / answerable,
                        "unanswerable", unanswerable, "refused", refused, "cases", rows);
            } else {
                result = Map.of("query", args[1], "knowledgeCount", store.count(),
                        "missingVectors", store.missingVectorCount(), "hits", store.search(args[1], 5));
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), result);
        }
    }
}
