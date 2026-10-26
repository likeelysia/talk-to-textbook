package io.github.likeelysia.formalagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用本地 stub HTTP 服务测 LocalEmbeddingClient —— 不依赖真的 8090,CI 里也能跑。
 */
class LocalEmbeddingClientTest {

    private HttpServer server;

    /** 起一个只会按固定状态码/正文应答的迷你服务,返回它的 base url。 */
    private String startServer(String path, int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
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

    private static AppConfig config(String url) {
        return new AppConfig(false, new AppConfig.Llm(5, 2, 1, 100, 100), null, null,
                new AppConfig.Embedding(url, "bge-small-zh-v1.5"), null, null, null);
    }

    @Test
    @DisplayName("响应多带 index 字段也要能解析(严格 mapper 下也不许炸)")
    void toleratesExtraIndexField() throws Exception {
        String url = startServer("/v1/embeddings", 200,
                "{\"object\":\"list\",\"model\":\"bge-small-zh-v1.5\","
                        + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2,0.3]}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}");

        // 故意传严格 mapper:宽容必须写在 DTO 上,不能指望调用方配什么 ObjectMapper。
        // 回归的是 2026-10-10 那个坑:严格 mapper + index 字段 → 每次 embed 都炸 → 检索静默退化成纯关键词。
        LocalEmbeddingClient client = new LocalEmbeddingClient(config(url), new ObjectMapper());

        assertThat(client.embed("位移")).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    @DisplayName("data[].index 乱序时按 index 还原,不能把向量配错文本")
    void reordersByIndex() throws Exception {
        String url = startServer("/v1/embeddings", 200,
                "{\"data\":[{\"index\":1,\"embedding\":[0.4,0.5]},"
                        + "{\"index\":0,\"embedding\":[0.1,0.2]}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}");
        LocalEmbeddingClient client = new LocalEmbeddingClient(config(url), new ObjectMapper());

        assertThat(client.embedAll(List.of("第一条", "第二条")))
                .containsExactly(new float[]{0.1f, 0.2f}, new float[]{0.4f, 0.5f});
    }

    @Test
    @DisplayName("空向量/非有限值必须拒绝,不能静默写入坏向量")
    void rejectsInvalidVector() throws Exception {
        String url = startServer("/v1/embeddings", 200,
                "{\"data\":[{\"index\":0,\"embedding\":[]}]}");
        LocalEmbeddingClient client = new LocalEmbeddingClient(config(url), new ObjectMapper());

        assertThatThrownBy(() -> client.embed("坏向量"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("空向量");
    }

    @Test
    @DisplayName("健康探测:200 → UP")
    void healthIsUpOn200() throws Exception {
        String url = startServer("/health", 200, "{\"status\":\"ok\"}");

        assertThat(new LocalEmbeddingClient(config(url), new ObjectMapper()).health()).isEqualTo("UP");
    }

    @Test
    @DisplayName("健康探测:503(模型还在加载)→ DOWN")
    void healthIsDownOn503() throws Exception {
        String url = startServer("/health", 503, "{\"error\":\"loading model\"}");

        assertThat(new LocalEmbeddingClient(config(url), new ObjectMapper()).health()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("健康探测:端口没人听 → DOWN(且不抛异常)")
    void healthIsDownWhenUnreachable() {
        // 1 号端口不会有人监听 → 连接直接被拒
        assertThat(new LocalEmbeddingClient(config("http://127.0.0.1:1"), new ObjectMapper()).health())
                .isEqualTo("DOWN");
    }
}
