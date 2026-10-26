package io.github.likeelysia.formalagent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.dto.EmbeddingRequest;
import io.github.likeelysia.formalagent.llm.dto.EmbeddingResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容的 embedding 客户端:本地 llama.cpp 或外部云服务共用。
 *
 * <p>本轮为外部服务增加的能力:
 * <ul>
 *   <li>HTTP 批量大小可配置(默认 16;百炼 v4 建议 10 或更小);</li>
 *   <li>端点路径可配置,避免在 base URL 已含 {@code /v1} 时重复拼接;</li>
 *   <li>处理 {@code data[].index},校验唯一/完整/范围并按 index 还原顺序;</li>
 *   <li>校验条数、非空、维度、有限数值和非零范数,坏向量不落盘;</li>
 *   <li>429/5xx 有上限退避,401/403/参数错误不重试;</li>
 *   <li>记录供应商返回的 usage,但未返回时标为未知而不是 0。</li>
 * </ul>
 */
@Component
public class LocalEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingClient.class);

    /** 默认 HTTP 子批;云服务可覆盖。 */
    private static final int DEFAULT_BATCH_SIZE = 16;

    /** 健康探测的超时(毫秒):/health 是轻量端点,超过这个数还没回就当它挂了。 */
    private static final long HEALTH_TIMEOUT_MS = 600;

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final AppConfig config;
    private final String apiKey;
    private final String healthPath;

    @org.springframework.beans.factory.annotation.Value("${fa.embedding.batch-size:16}")
    private int batchSize = DEFAULT_BATCH_SIZE;

    @org.springframework.beans.factory.annotation.Value("${fa.embedding.embeddings-path:/v1/embeddings}")
    private String embeddingsPath = "/v1/embeddings";

    @org.springframework.beans.factory.annotation.Value("${fa.embedding.dimensions:0}")
    private int expectedDimensions = 0;

    @org.springframework.beans.factory.annotation.Value("${fa.embedding.max-attempts:3}")
    private int maxAttempts = 3;

    @org.springframework.beans.factory.annotation.Value("${fa.embedding.retry-base-ms:500}")
    private long retryBaseMs = 500;

    @org.springframework.beans.factory.annotation.Value("${fa.embedding.timeout-seconds:20}")
    private int timeoutSeconds = 20;

    public LocalEmbeddingClient(AppConfig config, ObjectMapper mapper) {
        this(config, mapper, "", "/health");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LocalEmbeddingClient(AppConfig config, ObjectMapper mapper,
                                @org.springframework.beans.factory.annotation.Value("${fa.embedding.api-key:}") String apiKey,
                                @org.springframework.beans.factory.annotation.Value("${fa.embedding.health-path:/health}") String healthPath) {
        this.config = config;
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.healthPath = healthPath == null ? "" : healthPath.strip();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public String modelName() {
        return config.embedding().model();
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        int size = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += size) {
            out.addAll(embedBatch(texts.subList(i, Math.min(i + size, texts.size()))));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return requestBatch(texts);
            } catch (TransientApiException e) {
                if (attempt >= attempts) throw e;
                long backoff = Math.max(1, retryBaseMs) * (1L << Math.min(5, attempt - 1));
                log.warn("embedding 可重试失败,{}ms 后重试 {}/{}:{}",
                        backoff, attempt, attempts, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AgentException("embedding 重试被中断", interrupted);
                }
            }
        }
        throw new AgentException("embedding 重试次数耗尽");
    }

    private List<float[]> requestBatch(List<String> texts) {
        try {
            String body = mapper.writeValueAsString(new EmbeddingRequest(config.embedding().model(), texts));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingsUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = HttpCalls.send(
                    client, builder.build(), HttpResponse.BodyHandlers.ofString(), timeoutSeconds);
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                String detail = "embedding 服务返回错误(" + code + "):" + abbreviate(response.body());
                if (code == 429 || code >= 500) {
                    throw new TransientApiException(detail);
                }
                if (code == 401 || code == 403) {
                    throw new AgentException("embedding 鉴权失败,请检查 Key/模型权限");
                }
                throw new AgentException(detail);
            }

            EmbeddingResponse parsed = mapper.readValue(response.body(), EmbeddingResponse.class);
            List<float[]> vectors = validateAndOrder(parsed, texts.size());
            if (parsed.usage() != null) {
                log.info("embedding usage texts={} promptTokens={} totalTokens={}",
                        texts.size(), parsed.usage().prompt_tokens(), parsed.usage().total_tokens());
            } else {
                log.info("embedding usage texts={} usage=UNKNOWN", texts.size());
            }
            return vectors;
        } catch (JsonProcessingException e) {
            throw new AgentException("embedding JSON 处理失败:" + e.getMessage(), e);
        } catch (IOException e) {
            throw new TransientApiException("连接 embedding 服务失败(是不是没启动?):" + e.getMessage(), e);
        }
    }

    /** 按 index 还原批量顺序；没有 index 时只能按服务返回顺序(明确作为兼容分支)。 */
    private List<float[]> validateAndOrder(EmbeddingResponse parsed, int expected) {
        if (parsed == null || parsed.data() == null || parsed.data().size() != expected) {
            throw new AgentException("embedding 返回条数不对:期望 " + expected
                    + ",实际 " + (parsed == null || parsed.data() == null ? 0 : parsed.data().size()));
        }
        boolean hasAnyIndex = parsed.data().stream().anyMatch(i -> i != null && i.index() != null);
        boolean allIndex = parsed.data().stream().allMatch(i -> i != null && i.index() != null);
        if (hasAnyIndex && !allIndex) {
            throw new AgentException("embedding 返回 index 部分缺失,拒绝按猜测顺序使用");
        }

        EmbeddingResponse.Item[] ordered = new EmbeddingResponse.Item[expected];
        if (allIndex) {
            for (EmbeddingResponse.Item item : parsed.data()) {
                int idx = item.index();
                if (idx < 0 || idx >= expected || ordered[idx] != null) {
                    throw new AgentException("embedding 返回 index 越界或重复:" + idx);
                }
                ordered[idx] = item;
            }
        } else {
            for (int i = 0; i < expected; i++) ordered[i] = parsed.data().get(i);
        }

        List<float[]> out = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            float[] vector = ordered[i] == null ? null : ordered[i].embedding();
            if (vector == null || vector.length == 0) {
                throw new AgentException("embedding 返回空向量,index=" + i);
            }
            if (expectedDimensions > 0 && vector.length != expectedDimensions) {
                throw new AgentException("embedding 维度不符:期望 " + expectedDimensions
                        + ",实际 " + vector.length);
            }
            double norm = 0;
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new AgentException("embedding 含非有限数值,index=" + i);
                }
                norm += (double) value * value;
            }
            if (norm == 0) throw new AgentException("embedding 向量范数为 0,index=" + i);
            out.add(vector);
        }
        return out;
    }

    private String embeddingsUrl() {
        String base = config.embedding().url() == null ? "" : config.embedding().url().strip();
        String path = embeddingsPath == null ? "" : embeddingsPath.strip();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (path.isEmpty()) path = "/v1/embeddings";
        if (!path.startsWith("/")) path = "/" + path;
        return base + path;
    }

    @Override
    public String health() {
        if (healthPath.isBlank()) {
            return "UNKNOWN";
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.embedding().url()
                            + (healthPath.startsWith("/") ? healthPath : "/" + healthPath)))
                    .timeout(Duration.ofMillis(HEALTH_TIMEOUT_MS))
                    .GET();
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return (code >= 200 && code < 300) ? "UP" : "DOWN";
        } catch (IOException e) {
            return "DOWN";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "DOWN";
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 200 ? one : one.substring(0, 200) + "...";
    }
}
