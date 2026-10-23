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
import org.springframework.stereotype.Component;

/**
 * 本地 embedding 客户端:调本机 llama.cpp(llama-server --embeddings)的 OpenAI 兼容接口
 * {@code POST /v1/embeddings}。
 *
 * <p>刻意<b>不加重试</b>:本地服务,连不上就该"快失败"(几百毫秒),好让知识库立刻降级到关键词检索,
 * 而不是每搜一次都卡几十秒。
 */
@Component
public class LocalEmbeddingClient implements EmbeddingClient {

    /** 一次请求带几条文本(本地服务对批量输入有条数/显存限制,分批稳妥)。 */
    private static final int BATCH_SIZE = 16;

    /** 健康探测的超时(毫秒):/health 是轻量端点,超过这个数还没回就当它挂了,别拖慢调用方。 */
    private static final long HEALTH_TIMEOUT_MS = 600;

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final AppConfig config;
    private final String apiKey;
    private final String healthPath;

    /** 单次 embedding 请求超时;本地/云端都不能无限等。 */
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
                .connectTimeout(Duration.ofSeconds(2))       // 本地/云端都快速失败,别拖垮问答
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

    /**
     * 打 llama-server 自己的 {@code GET /health}(模型加载完回 200,加载中回 503)。
     *
     * <p>走的是轻量端点,**不消耗 embedding 算力**,所以 /api/health 可以随便被监控轮询。
     */
    @Override
    public String health() {
        if (healthPath.isBlank()) {
            // 云端服务未必有 /health;不假装 UP,交给 /api/health 显示 UNKNOWN
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

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            out.addAll(embedBatch(texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()))));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        try {
            String body = mapper.writeValueAsString(new EmbeddingRequest(config.embedding().model(), texts));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.embedding().url() + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = builder.build();

            HttpResponse<String> response = HttpCalls.send(
                    client, request, HttpResponse.BodyHandlers.ofString(), timeoutSeconds);
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                throw new TransientApiException("embedding 服务返回错误(" + code + "):" + abbreviate(response.body()));
            }
            EmbeddingResponse parsed = mapper.readValue(response.body(), EmbeddingResponse.class);
            if (parsed.data() == null || parsed.data().size() != texts.size()) {
                throw new TransientApiException("embedding 返回条数不对:" + abbreviate(response.body()));
            }
            return parsed.data().stream().map(EmbeddingResponse.Item::embedding).toList();

        } catch (JsonProcessingException e) {
            throw new AgentException("embedding JSON 处理失败:" + e.getMessage(), e);
        } catch (IOException e) {
            throw new TransientApiException("连接 embedding 服务失败(是不是没启动?):" + e.getMessage(), e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 200 ? one : one.substring(0, 200) + "...";
    }
}
