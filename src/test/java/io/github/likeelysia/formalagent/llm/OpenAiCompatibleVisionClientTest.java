package io.github.likeelysia.formalagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 视觉客户端「通用化」之后,真正要守住的三件事:
 *
 * <ol>
 *   <li><b>线上还是那套协议</b> —— 请求体结构、data URL 前缀、鉴权头,不能被"通用化"改坏(改了线上就瞎);</li>
 *   <li><b>厂商专属字段能配进来</b> —— {@code extra-body-json} 真的合进了请求体,不需要就一个字节都不多;</li>
 *   <li><b>密钥规则统一</b> —— 没配密钥时<b>在发请求之前</b>就报错,别让服务器白收一个 401。</li>
 * </ol>
 *
 * <p>用本机 {@code com.sun.net.httpserver} 当桩:不起 Spring、不发外网、可断言"服务器到底收到了什么"。
 * 注意这里 {@code new} 出来的是原始对象(没被 Spring 包代理),所以 {@code @Retryable} 不生效 ——
 * 正好可以直接断言"抛的是瞬时异常还是业务异常"。
 */
class OpenAiCompatibleVisionClientTest {

    @TempDir Path tmp;

    private static final String OK_BODY =
            "{\"id\":\"x\",\"usage\":{\"total_tokens\":9},"
                    + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  位移是位置的变化  \"}}]}";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个假端点:记下收到的请求体与鉴权头,按脚本作答。返回端点 URL。 */
    private String startServer(int status, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private Path image() throws IOException {
        Path p = tmp.resolve("page.jpg");
        Files.write(p, new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        return p;
    }

    /** 生产用的 mapper 是「忽略不认识的字段」的那份(这里是它的等价物)。 */
    private static ObjectMapper productionMapper() {
        return new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private OpenAiCompatibleVisionClient client(String url, String apiKey, String apiKeyEnv, String extraJson) {
        AppConfig.Vision vision =
                new AppConfig.Vision(url, "test-vl", apiKey, apiKeyEnv, "TestVision", 512, extraJson);
        AppConfig config = new AppConfig(false, new AppConfig.Llm(5, 2, 1, 100, 100),
                null, vision, null, null, null, null);
        return new OpenAiCompatibleVisionClient(config, productionMapper());
    }

    private static ObjectMapper json() {
        return new ObjectMapper();
    }

    @Test
    @DisplayName("请求体还是那套 OpenAI 兼容格式:model / messages[图+字] / max_tokens,鉴权头照旧")
    void sendsOpenAiCompatibleBody() throws Exception {
        String url = startServer(200, OK_BODY);
        OpenAiCompatibleVisionClient client = client(url, "test-key", null, "{\"thinking\":{\"type\":\"disabled\"}}");

        String answer = client.ask(image(), "照抄图上的字");

        assertThat(answer).isEqualTo("位移是位置的变化");        // 两端空白被 strip
        assertThat(requests.get()).isEqualTo(1);
        assertThat(lastAuth.get()).isEqualTo("Bearer test-key");

        JsonNode body = json().readTree(lastBody.get());
        assertThat(body.get("model").asText()).isEqualTo("test-vl");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(512);

        JsonNode message = body.get("messages").get(0);
        assertThat(message.get("role").asText()).isEqualTo("user");
        JsonNode imagePart = message.get("content").get(0);
        assertThat(imagePart.get("type").asText()).isEqualTo("image_url");
        assertThat(imagePart.get("image_url").get("url").asText()).startsWith("data:image/jpeg;base64,");
        assertThat(imagePart.has("text")).isFalse();            // 空字段不进 JSON(NON_NULL)
        JsonNode textPart = message.get("content").get(1);
        assertThat(textPart.get("type").asText()).isEqualTo("text");
        assertThat(textPart.get("text").asText()).isEqualTo("照抄图上的字");

        // 厂商专属字段确实被合并进来了
        assertThat(body.get("thinking").get("type").asText()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("不需要厂商专属字段时,请求体里一个字节都不多(换 OpenAI / 本地模型就靠这个)")
    void omitsExtraBodyWhenBlank() throws Exception {
        String url = startServer(200, OK_BODY);

        client(url, "test-key", null, "").ask(image(), "读图");

        JsonNode body = json().readTree(lastBody.get());
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.size()).isEqualTo(3);                    // model / messages / max_tokens
    }

    @Test
    @DisplayName("密钥:没配、环境变量也不存在 → 发请求之前就报错(服务器收不到任何东西)")
    void missingKeyFailsBeforeAnyRequest() throws Exception {
        String url = startServer(200, OK_BODY);
        OpenAiCompatibleVisionClient client = client(url, "", "FA_TEST_NO_SUCH_KEY_ZZZ", "");

        assertThatThrownBy(() -> client.ask(image(), "读图"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("FA_TEST_NO_SUCH_KEY_ZZZ");
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("密钥优先级:配置里直接写的值压过环境变量名")
    void directKeyWinsOverEnvName() throws Exception {
        String url = startServer(200, OK_BODY);

        client(url, "direct-key", "FA_TEST_NO_SUCH_KEY_ZZZ", "").ask(image(), "读图");

        assertThat(lastAuth.get()).isEqualTo("Bearer direct-key");
    }

    @Test
    @DisplayName("429 / 5xx / 空内容 → 瞬时异常(交给 @Retryable 重试)")
    void transientFailuresAreTransient() throws Exception {
        String limited = startServer(429, "{\"error\":{\"message\":\"rate limit\"}}");
        assertThatThrownBy(() -> client(limited, "k", null, "").ask(image(), "读图"))
                .isInstanceOf(TransientApiException.class);
        stopServer();

        String broken = startServer(503, "{\"error\":{\"message\":\"busy\"}}");
        assertThatThrownBy(() -> client(broken, "k", null, "").ask(image(), "读图"))
                .isInstanceOf(TransientApiException.class);
        stopServer();

        String blank = startServer(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  \"}}]}");
        assertThatThrownBy(() -> client(blank, "k", null, "").ask(image(), "读图"))
                .isInstanceOf(TransientApiException.class)
                .hasMessageContaining("空内容");
    }

    @Test
    @DisplayName("其它 4xx(参数 / 鉴权错)→ 业务异常,重试也没用")
    void clientErrorsAreNotTransient() throws Exception {
        String url = startServer(400, "{\"error\":{\"message\":\"bad model\"}}");

        assertThatThrownBy(() -> client(url, "k", null, "").ask(image(), "读图"))
                .isInstanceOf(AgentException.class)
                .isNotInstanceOf(TransientApiException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("显示名 / 模型指纹 / 健康度都来自配置,不是代码里的常量")
    void reportsProviderAndHealthFromConfig() throws Exception {
        String url = startServer(200, OK_BODY);

        OpenAiCompatibleVisionClient client = client(url, "test-key", null, "");

        assertThat(client.provider()).isEqualTo("TestVision");
        assertThat(client.modelName()).isEqualTo("test-vl");
        assertThat(client.health()).isEqualTo("UP");

        OpenAiCompatibleVisionClient noKey = client(url, "", "FA_TEST_NO_SUCH_KEY_ZZZ", "");
        assertThat(noKey.health()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("extra-body-json 写错 → 装配(构造)就报错,不用等第一次请求才炸")
    void malformedExtraBodyFailsFast() {
        assertThatThrownBy(() -> client("http://127.0.0.1:1/v1/chat/completions", "k", null, "{oops"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("extra-body-json");
    }

    @Test
    @DisplayName("把 URL / 模型写错(没配)→ 报的是清楚的配置错,而不是一个莫名其妙的网络异常")
    void unconfiguredVisionExplainsItself() {
        OpenAiCompatibleVisionClient client = client("", "k", null, "");

        assertThatThrownBy(() -> client.ask(Path.of("nope.jpg"), "读图"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("fa.vision.api-url");
        assertThat(client.health()).isEqualTo("DOWN");
    }
}
