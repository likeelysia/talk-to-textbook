package io.github.likeelysia.formalagent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.config.ApiKeys;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.dto.ChatResponse;
import io.github.likeelysia.formalagent.llm.dto.VisionMessage;
import io.github.likeelysia.formalagent.log.ApiLogger;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容视觉客户端:图片 → 多模态模型 → 文字。全项目<b>唯一</b>的视觉实现。
 *
 * <p><b>为什么只有一个实现</b>:国内云厂商 + 本地推理服务的视觉接口几乎都是「OpenAI 兼容」的
 * (阿里百炼 / 智谱 GLM-4V / 火山方舟豆包 / 硅基流动 / 本地 Ollama / LM Studio / OpenAI 兼容层),
 * 请求体就是 {@code {model, messages[], max_tokens}} 这一套。剩下的差异只有三件事,全部搬进配置:
 * <ol>
 *   <li><b>端点与模型名</b> —— {@code fa.vision.api-url} / {@code fa.vision.model}</li>
 *   <li><b>密钥从哪来</b> —— {@code fa.vision.api-key} / {@code fa.vision.api-key-env}(变量名也是配置)</li>
 *   <li><b>厂商专属字段</b> —— {@code fa.vision.extra-body-json}(如 K2.6 的 {@code {"thinking":{"type":"disabled"}}})</li>
 * </ol>
 * 于是「换模型」= 改 yml,而不是改代码;要多厂商并存就用 profile(见 application.yml 的 qwen / ollama 预设)。
 *
 * <p><b>为什么请求体用 {@link LinkedHashMap} 而不是固定 record</b>:record 加不了动态字段,
 * 而厂商专属字段必须能原样合并进来。反过来,消息结构与配图格式那部分依旧是强类型的
 * ({@link VisionMessage}),不该为了"通用"把全部结构都退化成 Map。
 *
 * <p>错误分派保持不变:<b>429 / 5xx / 空内容</b> → {@link TransientApiException}(交给 @Retryable 重试);
 * <b>其它 4xx</b> → {@link AgentException}(参数/鉴权错,重试也没用)。
 */
@Component
public class OpenAiCompatibleVisionClient implements VisionClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleVisionClient.class);

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final AppConfig config;

    /** 厂商专属字段:装配时就解析好 —— JSON 写错的话启动即报错,不用等第一次请求才炸 */
    private final Map<String, Object> extraBody;

    public OpenAiCompatibleVisionClient(AppConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.llm().connectTimeoutSeconds()))
                .build();
        this.extraBody = parseExtraBody(visionOrNull());
        log.info("视觉模型已装配:provider={} model={} extra-body={}", provider(), modelName(), extraBody);
    }

    @Override
    public String provider() {
        AppConfig.Vision v = visionOrNull();
        return (v == null || v.provider() == null || v.provider().isBlank()) ? "unknown" : v.provider();
    }

    @Override
    public String modelName() {
        AppConfig.Vision v = visionOrNull();
        return (v == null || v.model() == null || v.model().isBlank()) ? "unknown" : v.model();
    }

    /**
     * 轻量健康探测:<b>不真去打一次视觉接口</b>(贵、慢、还费配额),
     * 只核对「端点 / 模型 / 密钥配齐没有」。所以它的语义是「配置可用」而不是「刚才调用成功」。
     */
    @Override
    public String health() {
        AppConfig.Vision v = visionOrNull();
        if (v == null || isBlank(v.apiUrl()) || isBlank(v.model())) {
            return "DOWN";
        }
        return ApiKeys.has(v.apiKey(), v.apiKeyEnv()) ? "UP" : "DOWN";
    }

    @Retryable(
            value = TransientApiException.class,
            maxAttemptsExpression = "${fa.llm.max-attempts:5}",
            backoff = @Backoff(delayExpression = "${fa.llm.retry-base-ms:2000}",
                    maxDelayExpression = "${fa.llm.retry-max-ms:20000}",
                    multiplier = 2.0, random = false))
    @Override
    public String ask(Path image, String prompt) {
        AppConfig.Vision v = requireVision();
        String apiKey = ApiKeys.resolve(v.apiKey(), v.apiKeyEnv());   // 没配密钥:在发请求之前就报错
        try {
            String dataUrl = toDataUrl(image);
            String body = mapper.writeValueAsString(
                    requestBody(v, VisionMessage.ofImageAndText(dataUrl, prompt)));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(v.apiUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(config.llm().requestTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = HttpCalls.send(
                    client, request, HttpResponse.BodyHandlers.ofString(),
                    config.llm().requestTimeoutSeconds());
            long cost = System.currentTimeMillis() - start;

            ApiLogger.record(v.model(), response.statusCode(), cost,
                    body.length(), response.body().length());

            int code = response.statusCode();
            if (code == 429 || code >= 500) {
                throw new TransientApiException(tag() + "暂时不可用(" + code + "):" + abbreviate(response.body()));
            }
            if (code < 200 || code >= 300) {
                throw new AgentException(tag() + "返回错误(" + code + "):" + abbreviate(response.body()));
            }

            ChatResponse parsed = mapper.readValue(response.body(), ChatResponse.class);
            if (parsed.choices() == null || parsed.choices().isEmpty()) {
                throw new TransientApiException(tag() + "返回里没有 choices:" + abbreviate(response.body()));
            }
            String content = parsed.choices().get(0).message().content();
            if (content == null || content.isBlank()) {
                // 思考型模型偶尔把答案全塞进 reasoning 字段、content 留空 → 当瞬时故障重试
                throw new TransientApiException(tag() + "返回空内容(疑似思考模式吞了输出)");
            }
            return content.strip();

        } catch (JsonProcessingException e) {
            throw new AgentException("JSON 处理失败:" + e.getMessage(), e);
        } catch (IOException e) {
            throw new TransientApiException("调用" + tag() + "失败(IO):" + e.getMessage(), e);
        }
    }

    /**
     * 请求体 = 固定三件套 + 厂商专属字段。
     *
     * <p>顺序有意为之:厂商字段<b>放在最后</b>合并 —— 万一将来需要覆盖
     * {@code max_tokens} 这类标准字段(有些厂商改叫 {@code max_completion_tokens}),也不必再改代码。
     */
    private Map<String, Object> requestBody(AppConfig.Vision v, VisionMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", v.model());
        body.put("messages", List.of(message));
        body.put("max_tokens", v.maxTokens());
        body.putAll(extraBody);
        return body;
    }

    /** 日志 / 报错里显示的名字,取配置的 provider(不再把「Kimi」写死在代码里)。 */
    private String tag() {
        return provider() + " 视觉";
    }

    private AppConfig.Vision visionOrNull() {
        return config == null ? null : config.vision();
    }

    /** 真要发请求了,才要求配置齐备(构造期只做"能装配就装配",免得没配视觉的部署起不来)。 */
    private AppConfig.Vision requireVision() {
        AppConfig.Vision v = visionOrNull();
        if (v == null) {
            throw new AgentException("没配置视觉模型(fa.vision.api-url / model / 密钥)");
        }
        if (isBlank(v.apiUrl())) {
            throw new AgentException("fa.vision.api-url 不能为空");
        }
        if (isBlank(v.model())) {
            throw new AgentException("fa.vision.model 不能为空");
        }
        return v;
    }

    private Map<String, Object> parseExtraBody(AppConfig.Vision vision) {
        String json = vision == null ? null : vision.extraBodyJson();
        if (isBlank(json)) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new AgentException("fa.vision.extra-body-json 不是合法 JSON:" + e.getOriginalMessage(), e);
        }
    }

    /** 图片 → data URL(base64)。 */
    private String toDataUrl(Path image) throws IOException {
        byte[] bytes = Files.readAllBytes(image);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return "data:" + mimeOf(image) + ";base64," + base64;
    }

    private static String mimeOf(Path image) {
        String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        return "image/jpeg";   // .jpg / .jpeg
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 300 ? one : one.substring(0, 300) + "...";
    }
}
