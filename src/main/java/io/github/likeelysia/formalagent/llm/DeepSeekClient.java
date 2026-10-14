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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    private final HttpClient client;                     // 重对象:构造时建一份,复用
    private final ObjectMapper mapper;                   // ← 容器注入的统一 JSON 序列化器
    private final AppConfig config;                      // ← 配置也由容器塞进来

    public DeepSeekClient(AppConfig config, ObjectMapper mapper) {   // 单构造器,@Autowired 可省
        this.config = config;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.llm().connectTimeoutSeconds()))
                .build();
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
        ChatRequest request = new ChatRequest(
                config.deepseek().model(), messages,
                config.deepseek().maxTokens(), null, tools, Boolean.TRUE);
        return postStream(request, onDelta);
    }

    /** 真正发请求的地方 —— 普通对话和工具对话共用这一份。 */
    private ChatResponse post(ChatRequest request) {
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
     * <p>为什么用 {@code BodyHandlers.ofLines()}:它把响应体变成一行行的 Stream,
     * 我们只要认 {@code data:} 开头的行 —— 解析是<b>惰性</b>的,模型吐一行我们就收一行,
     * 这才是"流式"的意义(换成 {@code ofString()} 会等到全部生成完,白搭)。
     */
    private AssistantTurn postStream(ChatRequest request, Consumer<String> onDelta) {
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
        HttpResponse<Stream<String>> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            // 还没读到任何一行 → 安全重试
            throw new TransientApiException("网络异常(尚未开始输出,可重试):" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("调用被中断", e);
        }

        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            String errorBody = joinFirstLines(response.body());
            ApiLogger.record(config.deepseek().model(), code, System.currentTimeMillis() - start,
                    body.length(), errorBody.length());
            if (code == 429 || code >= 500) {
                throw new TransientApiException("DeepSeek 暂时不可用(" + code + "):" + abbreviate(errorBody));
            }
            throw new AgentException("DeepSeek 返回错误(" + code + "):" + abbreviate(errorBody));
        }

        return consume(response.body(), onDelta, start, body.length());
    }

    /** 逐行消费 SSE:累积正文 + 拼接工具调用碎片。 */
    private AssistantTurn consume(Stream<String> lines, Consumer<String> onDelta, long start, int requestLength) {
        StringBuilder content = new StringBuilder();
        Map<Integer, PartialCall> partials = new LinkedHashMap<>();
        boolean emitted = false;

        try {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next().strip();
                // 只认 data: 行 —— ": ping" 这类心跳注释、event:/id: 行一律跳过
                if (line.isEmpty() || !line.startsWith(DATA_PREFIX)) {
                    continue;
                }
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
        } catch (IOException e) {                          // JsonProcessingException 是它的子类
            throw streamFailure(emitted, "流式解析失败:" + e.getMessage(), e);
        } catch (RuntimeException e) {                     // 连接中途断开等
            throw streamFailure(emitted, "流式读取失败:" + e.getMessage(), e);
        } finally {
            lines.close();
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

    /** 错误体不能无限读:取前几行就够了。 */
    private static String joinFirstLines(Stream<String> lines) {
        try (lines) {
            return lines.limit(20).collect(Collectors.joining(" "));
        }
    }

    /** 出错时截断返回体,避免把整页 HTML/JSON 刷进日志。 */
    private static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 300 ? one : one.substring(0, 300) + "...";
    }
}
