package io.github.likeelysia.formalagent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.config.ApiKeys;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.llm.dto.AssistantTurn;
import io.github.likeelysia.formalagent.llm.dto.ChatChunk;
import io.github.likeelysia.formalagent.llm.dto.ChatRequest;
import io.github.likeelysia.formalagent.llm.dto.ChatResponse;
import io.github.likeelysia.formalagent.llm.dto.ToolCall;
import io.github.likeelysia.formalagent.llm.dto.ToolDefinition;
import io.github.likeelysia.formalagent.log.ApiLogger;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    /** SSE 每条数据行的前缀:{"data: {...}"}。 */
    private static final String DATA_PREFIX = "data:";

    /** 服务端结束标记。 */
    private static final String DONE = "[DONE]";

    /** 错误体只读 4096 字节,固定缓冲;不等待换行/EOF,也不随单行增长。 */
    private static final int ERROR_BODY_MAX_BYTES = 4096;
    private static final int ERROR_BODY_READ_BUFFER = 1024;

    private final HttpClient client;                     // 重对象:构造时建一份,复用
    private final ObjectMapper mapper;                   // ← 容器注入的统一 JSON 序列化器
    private final AppConfig config;                      // ← 配置也由容器塞进来
    private final ModelConcurrencyLimiter limiter;
    private final long streamIdleTimeoutMillis;
    private final long streamTotalTimeoutMillis;
    private final ScheduledExecutorService streamGuardScheduler;

    public DeepSeekClient(AppConfig config, ObjectMapper mapper) {
        this(config, mapper, new ModelConcurrencyLimiter(2, 1, 10), 60, 300);
    }

    public DeepSeekClient(AppConfig config, ObjectMapper mapper, ModelConcurrencyLimiter limiter) {
        this(config, mapper, limiter, 60, 300);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeepSeekClient(AppConfig config, ObjectMapper mapper, ModelConcurrencyLimiter limiter,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${fa.llm.stream-idle-timeout-seconds:60}") long idleSeconds,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${fa.llm.stream-total-timeout-seconds:300}") long totalSeconds) {
        this.config = config;
        this.mapper = mapper;
        this.limiter = limiter;
        this.streamIdleTimeoutMillis = Math.max(1, idleSeconds) * 1000L;
        this.streamTotalTimeoutMillis = Math.max(1, totalSeconds) * 1000L;
        this.streamGuardScheduler = StreamReadGuard.daemonScheduler();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.llm().connectTimeoutSeconds()))
                .build();
    }

    @PreDestroy
    void shutdownStreamGuard() {
        streamGuardScheduler.shutdownNow();
    }

    @Retryable(
            value = TransientApiException.class,          // 只重试"瞬时故障"
            maxAttemptsExpression = "${fa.llm.max-attempts:5}",
            backoff = @Backoff(
                    delayExpression = "${fa.llm.retry-base-ms:2000}",
                    maxDelayExpression = "${fa.llm.retry-max-ms:20000}",
                    multiplier = 2.0,
                    random = false))
    @Override
    public String chat(List<Message> history, ChatOptions options) {
        ChatRequest request = new ChatRequest(
                config.deepseek().model(),
                history.stream().map(AgentMessage::of).toList(),
                config.deepseek().maxTokens(),
                options.jsonMode() ? ChatRequest.ResponseFormat.JSON : null,
                null);
        String content = post(request).choices().get(0).message().content();
        return content == null ? "" : content.strip();
    }

    @Retryable(
            value = TransientApiException.class,
            maxAttemptsExpression = "${fa.llm.max-attempts:5}",
            backoff = @Backoff(
                    delayExpression = "${fa.llm.retry-base-ms:2000}",
                    maxDelayExpression = "${fa.llm.retry-max-ms:20000}",
                    multiplier = 2.0,
                    random = false))
    @Override
    public AssistantTurn chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
        ChatRequest request = new ChatRequest(
                config.deepseek().model(), messages,
                config.deepseek().maxTokens(), null, tools);
        ChatResponse.ChatMessage message = post(request).choices().get(0).message();
        return new AssistantTurn(message.content(), message.toolCalls());
    }

    /**
     * 流式版:边收边把正文回调出去。
     *
     * <p>重试纪律靠异常类型来兜:中间断了但<b>已经吐过字</b>({@code emitted})就抛
     * {@link AgentException}(@Retryable 不认它 → 不会重试),否则抛
     * {@link TransientApiException} 让它重试。这样"重试"永远不会让用户看到两遍半截回答。
     */
    @Retryable(
            value = TransientApiException.class,
            maxAttemptsExpression = "${fa.llm.max-attempts:5}",
            backoff = @Backoff(
                    delayExpression = "${fa.llm.retry-base-ms:2000}",
                    maxDelayExpression = "${fa.llm.retry-max-ms:20000}",
                    multiplier = 2.0,
                    random = false))
    @Override
    public AssistantTurn chatWithToolsStream(List<AgentMessage> messages, List<ToolDefinition> tools,
                                             Consumer<String> onDelta) {
        return chatWithToolsStream(messages, tools, onDelta, () -> false);
    }

    @Retryable(
            value = TransientApiException.class,
            maxAttemptsExpression = "${fa.llm.max-attempts:5}",
            backoff = @Backoff(
                    delayExpression = "${fa.llm.retry-base-ms:2000}",
                    maxDelayExpression = "${fa.llm.retry-max-ms:20000}",
                    multiplier = 2.0,
                    random = false))
    @Override
    public AssistantTurn chatWithToolsStream(List<AgentMessage> messages, List<ToolDefinition> tools,
                                             Consumer<String> onDelta, BooleanSupplier cancelled) {
        ChatRequest request = new ChatRequest(
                config.deepseek().model(), messages,
                config.deepseek().maxTokens(), null, tools, Boolean.TRUE);
        return postStream(request, onDelta, cancelled);
    }

    /** 所有文字模型请求的唯一入口:名额覆盖完整 HTTP 调用(含重试)。 */
    private ChatResponse post(ChatRequest request) {
        try (ModelConcurrencyLimiter.Permit ignored = limiter.acquire()) {
            return postWithPermit(request);
        }
    }

    /** 真正发请求的地方 —— 普通对话和工具对话共用这一份。 */
    private ChatResponse postWithPermit(ChatRequest request) {
        String apiKey = apiKey();
        try {
            String body = mapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(config.deepseek().apiUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(config.llm().requestTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = HttpCalls.send(
                    client, httpRequest, HttpResponse.BodyHandlers.ofString(),
                    config.llm().requestTimeoutSeconds());
            long cost = System.currentTimeMillis() - start;

            ApiLogger.record(config.deepseek().model(), response.statusCode(), cost,
                    body.length(), response.body().length());
            if (config.debug()) log.debug("请求体:{}", body);

            int code = response.statusCode();
            if (code == 429 || code >= 500) {
                // 瞬时故障 → 抛可重试异常(交给 @Retryable 重试)
                throw new TransientApiException("DeepSeek 暂时不可用(" + code + "):" + abbreviate(response.body()));
            }
            if (code < 200 || code >= 300) {
                // 业务错误 → 直接失败,不重试
                throw new AgentException("DeepSeek 返回错误(" + code + "):" + abbreviate(response.body()));
            }

            ChatResponse parsed = mapper.readValue(response.body(), ChatResponse.class);
            if (parsed.choices() == null || parsed.choices().isEmpty()) {
                throw new TransientApiException("DeepSeek 返回里没有 choices:" + abbreviate(response.body()));
            }
            return parsed;

        } catch (JsonProcessingException e) {          // 注意:它是 IOException 的子类,要先接
            throw new AgentException("JSON 处理失败:" + e.getMessage(), e);
        } catch (IOException e) {
            throw new TransientApiException("网络异常:" + e.getMessage(), e);
        }
    }

    /**
     * 发流式请求并逐行消费 SSE。
     *
     * <p>响应体用 {@code BodyHandlers.ofInputStream()} 拿字节流,再用 UTF-8 的
     * {@link BufferedReader} 按行读 —— 这样成功流可以逐行解析,错误流则能在同一个
     * 看门狗下按字节截断,不必等换行或 EOF。
     */
    private AssistantTurn postStream(ChatRequest request, Consumer<String> onDelta,
                                     BooleanSupplier cancelled) {
        try (ModelConcurrencyLimiter.Permit ignored = limiter.acquire()) {
            return postStreamWithPermit(request, onDelta, cancelled);
        }
    }

    private AssistantTurn postStreamWithPermit(ChatRequest request, Consumer<String> onDelta,
                                               BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new AgentException("用户已取消本次回答");
        }
        String apiKey = apiKey();
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new AgentException("JSON 处理失败:" + e.getMessage(), e);
        }
        if (config.debug()) log.debug("请求体(stream):{}", body);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.deepseek().apiUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(config.llm().requestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<InputStream> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            // 还没读到任何一行 → 安全重试
            throw new TransientApiException("网络异常(尚未开始输出,可重试):" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("调用被中断", e);
        }

        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            String errorBody;
            // 错误响应体也必须受总时限/空闲时限保护,并且按字节截断,不等待换行/EOF。
            try (StreamReadGuard guard = new StreamReadGuard(
                    response.body(), start, streamIdleTimeoutMillis, streamTotalTimeoutMillis,
                    streamGuardScheduler, "错误响应数据")) {
                errorBody = readErrorBody(response.body(), guard);
            } catch (ErrorBodyTimeoutException e) {
                if (code == 429 || code >= 500) {
                    throw new TransientApiException("DeepSeek 错误响应读取超时(" + code + "):" + e.getMessage(), e);
                }
                throw new AgentException("DeepSeek 错误响应读取超时(" + code + "):" + e.getMessage(), e);
            }
            ApiLogger.record(config.deepseek().model(), code, System.currentTimeMillis() - start,
                    body.length(), errorBody.length());
            if (code == 429 || code >= 500) {
                throw new TransientApiException("DeepSeek 暂时不可用(" + code + "):" + abbreviate(errorBody));
            }
            throw new AgentException("DeepSeek 返回错误(" + code + "):" + abbreviate(errorBody));
        }

        try (StreamReadGuard guard = new StreamReadGuard(
                response.body(), start, streamIdleTimeoutMillis, streamTotalTimeoutMillis,
                streamGuardScheduler)) {
            return consume(response.body(), onDelta, start, body.length(), guard, cancelled);
        }
    }

    /** 逐行消费 SSE:累积正文 + 拼接工具调用碎片。 */
    private AssistantTurn consume(InputStream body, Consumer<String> onDelta, long start,
                                  int requestLength, StreamReadGuard guard,
                                  BooleanSupplier cancelled) {
        StringBuilder content = new StringBuilder();
        Map<Integer, PartialCall> partials = new LinkedHashMap<>();
        boolean emitted = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                if (guard.isTimedOut()) {
                    throw new AgentException(guard.reason());
                }
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new AgentException("用户已取消本次回答");
                }
                String line = raw.strip();
                // 只认 data: 行 —— ": ping" 这类心跳注释、event:/id: 行一律跳过
                if (line.isEmpty() || !line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                guard.touchData();
                String payload = line.substring(DATA_PREFIX.length()).strip();
                if (DONE.equals(payload)) {
                    break;
                }
                ChatChunk.Delta delta = firstDelta(mapper.readValue(payload, ChatChunk.class));
                if (delta == null) {
                    continue;
                }
                if (delta.content() != null && !delta.content().isEmpty()) {
                    content.append(delta.content());
                    emitted = true;                       // ← 一旦吐过字,就不许重试了
                    onDelta.accept(delta.content());
                }
                if (delta.toolCalls() != null) {
                    for (ChatChunk.ToolCallDelta fragment : delta.toolCalls()) {
                        merge(partials, fragment);
                    }
                }
            }
            if (guard.isTimedOut()) {
                throw new AgentException(guard.reason());
            }
        } catch (IOException e) {                          // JsonProcessingException 是它的子类
            if (guard.isTimedOut()) {
                throw new AgentException(guard.reason(), e);
            }
            throw streamFailure(emitted, "流式解析失败:" + e.getMessage(), e);
        } catch (RuntimeException e) {                     // 连接中途断开等
            if (guard.isTimedOut()) {
                throw new AgentException(guard.reason(), e);
            }
            throw streamFailure(emitted, "流式读取失败:" + e.getMessage(), e);
        }

        ApiLogger.record(config.deepseek().model(), 200, System.currentTimeMillis() - start,
                requestLength, content.length());

        List<ToolCall> calls = partials.isEmpty()
                ? null
                : partials.values().stream().map(PartialCall::toToolCall).toList();
        return new AssistantTurn(content.isEmpty() ? null : content.toString(), calls);
    }

    /** 已经吐过字就只能"报错收场",不能再重试了(否则用户会看到两遍半截回答)。 */
    private static RuntimeException streamFailure(boolean emitted, String message, Exception cause) {
        return emitted
                ? new AgentException(message + "(已输出部分内容,不再重试)", cause)
                : new TransientApiException(message, cause);
    }

    private ChatChunk.Delta firstDelta(ChatChunk chunk) {
        if (chunk.choices() == null || chunk.choices().isEmpty()) {
            return null;                                  // 有些实现会补一条只带 usage 的收尾块
        }
        return chunk.choices().get(0).delta();
    }

    /** 把同一 index 的碎片拼到同一个调用上(name / arguments 都可能被切成好几段)。 */
    private static void merge(Map<Integer, PartialCall> partials, ChatChunk.ToolCallDelta fragment) {
        int slot = fragment.index() == null ? partials.size() : fragment.index();
        PartialCall call = partials.computeIfAbsent(slot, key -> new PartialCall());
        if (fragment.id() != null) {
            call.id = fragment.id();
        }
        if (fragment.function() != null) {
            if (fragment.function().name() != null) {
                call.name.append(fragment.function().name());
            }
            if (fragment.function().arguments() != null) {
                call.args.append(fragment.function().arguments());
            }
        }
    }

    /** 一个正在拼接的工具调用。 */
    private static final class PartialCall {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder args = new StringBuilder();

        private ToolCall toToolCall() {
            return new ToolCall(id, "function", new ToolCall.Function(name.toString(), args.toString()));
        }
    }

    /**
     * 取密钥(与是否流式无关):配置里直接写的优先,否则读 {@code fa.deepseek.api-key-env} 指的环境变量。
     *
     * <p>与视觉那边共用 {@link ApiKeys} —— 「密钥从哪来」只有一套规则,不再各写一份。
     */
    private String apiKey() {
        return ApiKeys.resolve(config.deepseek().apiKey(), config.deepseek().apiKeyEnv());
    }

    /**
     * 错误体按字节读取:最多 4096 字节,固定 1024 字节缓冲。
     *
     * <p>不依赖完整一行、完整正文或 EOF;达到上限立刻返回,由外层关闭响应体。
     * UTF-8 解码使用替换字符,截断落在多字节字符中间也不会再抛异常。
     */
    private static String readErrorBody(InputStream body, StreamReadGuard guard) {
        byte[] out = new byte[ERROR_BODY_MAX_BYTES];
        byte[] buffer = new byte[ERROR_BODY_READ_BUFFER];
        int total = 0;
        try {
            while (total < ERROR_BODY_MAX_BYTES) {
                if (guard.isTimedOut()) break;
                int want = Math.min(buffer.length, ERROR_BODY_MAX_BYTES - total);
                int n = body.read(buffer, 0, want);
                if (n < 0) break;
                if (n == 0) continue;
                System.arraycopy(buffer, 0, out, total, n);
                total += n;
                guard.touch();
            }
        } catch (IOException e) {
            if (guard.isTimedOut()) {
                throw new ErrorBodyTimeoutException(guard.reason(), e);
            }
            throw new ErrorBodyTimeoutException("读取错误响应失败: " + e.getMessage(), e);
        }
        if (guard.isTimedOut()) {
            throw new ErrorBodyTimeoutException(guard.reason(), null);
        }
        // new String 对截断的 UTF-8 序列会用替换字符,不会抛出编码异常
        return new String(out, 0, total, StandardCharsets.UTF_8);
    }

    /** 非 2xx 响应体超时;由调用方按状态码决定可重试还是直接失败。 */
    private static final class ErrorBodyTimeoutException extends RuntimeException {
        ErrorBodyTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 出错时截断返回体,避免把整页 HTML/JSON 刷进日志。 */
    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 300 ? one : one.substring(0, 300) + "...";
    }
}
