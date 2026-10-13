package io.github.likeelysia.formalagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import io.github.likeelysia.formalagent.llm.dto.AssistantTurn;
import io.github.likeelysia.formalagent.llm.dto.ToolDefinition;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 流式解析:用一个假 SSE 服务喂数据,验证三件事 ——
 * 逐字回调、工具调用碎片拼接、以及"重试纪律"(吐过字就不许重试)。
 *
 * <p>不依赖真的 DeepSeek(也不花一分钱):服务是本地 stub,key 只要有环境变量就行。
 */
class DeepSeekClientStreamTest {

    private HttpServer server;

    /**
     * @param body           服务要吐的正文(SSE 文本)
     * @param declaredLength 声明的 Content-Length;<b>故意写得比实际字节多</b>就能模拟"说到一半断线"
     */
    private String startServer(int status, String body, int declaredLength) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, declaredLength >= 0 ? declaredLength : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * "吐一句就断"的假服务:声明得比实际多,写完 firstChunk 停一下再关 ——
     * 保证客户端能先把第一句收到(这样才测得到"已吐字就不重试")。
     */
    private String startTruncatingServer(String firstChunk, long pauseMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = firstChunk.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length + 500);      // 说好还有 500 字节,其实没有
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
                try {
                    Thread.sleep(pauseMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static DeepSeekClient client(String url) {
        AppConfig config = new AppConfig(false,
                new AppConfig.Llm(5, 2, 1, 100, 100),
                new AppConfig.Deepseek(url + "/chat/completions", "deepseek-chat", "test-key", null, 1024),
                null, null, null, null, null);
        return new DeepSeekClient(config, new ObjectMapper());
    }

    private static List<AgentMessage> messages() {
        return List.of(AgentMessage.user("位移是什么"));
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    @Test
    @DisplayName("正文:逐字回调,返回值是拼好的完整回答")
    void streamsTextDeltas() throws Exception {
        String url = startServer(200,
                data("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"位\"}}]}")
                        + data("{\"choices\":[{\"delta\":{\"content\":\"移是\"}}]}")
                        + data("{\"choices\":[{\"delta\":{\"content\":\"位置的变化\"}}]}")
                        + data("[DONE]"),
                -1);

        List<String> deltas = new ArrayList<>();
        AssistantTurn turn = client(url).chatWithToolsStream(messages(), List.of(), deltas::add);

        assertThat(deltas).containsExactly("位", "移是", "位置的变化");
        assertThat(turn.content()).isEqualTo("位移是位置的变化");
        assertThat(turn.wantsTools()).isFalse();
    }

    @Test
    @DisplayName("工具调用:被拆成碎片的 name/arguments 要按 index 拼回一个完整调用")
    void assemblesToolCallFragments() throws Exception {
        String url = startServer(200,
                data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"search_\",\"arguments\":\"\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"name\":\"knowledge\",\"arguments\":\"{\\\"query\\\":\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"位移\\\"}\"}}]}}]}")
                        + data("[DONE]"),
                -1);

        List<String> deltas = new ArrayList<>();
        AssistantTurn turn = client(url).chatWithToolsStream(messages(), List.of(), deltas::add);

        assertThat(deltas).isEmpty();                        // 工具轮不吐正文
        assertThat(turn.wantsTools()).isTrue();
        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(turn.toolCalls().get(0).function().name()).isEqualTo("search_knowledge");
        assertThat(turn.toolCalls().get(0).function().arguments()).isEqualTo("{\"query\":\"位移\"}");
    }

    @Test
    @DisplayName("心跳/无关事件行要被忽略:': ping'、event:、空行都不算内容")
    void ignoresHeartbeatAndNonDataLines() throws Exception {
        String url = startServer(200,
                ": ping\n\n"
                        + "event: message\n"
                        + data("{\"choices\":[]}")                        // 只带 usage 的收尾块
                        + data("{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}")
                        + data("[DONE]"),
                -1);

        List<String> deltas = new ArrayList<>();
        AssistantTurn turn = client(url).chatWithToolsStream(messages(), List.of(), deltas::add);

        assertThat(deltas).containsExactly("好");
        assertThat(turn.content()).isEqualTo("好");
    }

    @Test
    @DisplayName("重试纪律:还没吐字就断 -> TransientApiException(可重试)")
    void failureBeforeAnyOutputIsRetryable() throws Exception {
        String url = startServer(200, "", 999);                  // 声明 999 字节,一个也不给

        assertThatThrownBy(() -> client(url).chatWithToolsStream(messages(), List.of(), t -> { }))
                .isInstanceOf(TransientApiException.class);
    }

    @Test
    @DisplayName("重试纪律:已经吐过字才断 -> AgentException(绝不重试,否则用户看到两遍半截回答)")
    void failureAfterOutputIsNotRetryable() throws Exception {
        String half = data("{\"choices\":[{\"delta\":{\"content\":\"位移\"}}]}");
        String url = startTruncatingServer(half, 150);              // 吐了一句就断

        List<String> deltas = new ArrayList<>();
        assertThatThrownBy(() -> client(url).chatWithToolsStream(messages(), List.of(), deltas::add))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("不再重试");

        assertThat(deltas).containsExactly("位移");                  // 确实已经吐给前端了
    }

    @Test
    @DisplayName("业务错误(400):直接失败,不重试")
    void businessErrorIsNotRetried() throws Exception {
        String url = startServer(400, "{\"error\":\"bad request\"}", -1);

        assertThatThrownBy(() -> client(url).chatWithToolsStream(messages(), List.of(), t -> { }))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("服务端 5xx:可重试(TransientApiException)")
    void serverErrorIsTransient() throws Exception {
        String url = startServer(503, "{\"error\":\"overloaded\"}", -1);

        assertThatThrownBy(() -> client(url).chatWithToolsStream(messages(), List.of(), t -> { }))
                .isInstanceOf(TransientApiException.class);
    }
}
