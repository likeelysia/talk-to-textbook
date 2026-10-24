package io.github.likeelysia.formalagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.likeelysia.formalagent.config.AppConfig;
import io.github.likeelysia.formalagent.exception.AgentException;
import io.github.likeelysia.formalagent.llm.dto.AgentMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 用本地假模型验证流式响应体超时、断开和名额释放；不调用真实模型。 */
class DeepSeekStreamTimeoutTest {

    private HttpServer server;
    private CountDownLatch release;

    @AfterEach
    void stop() {
        if (release != null) release.countDown();
        if (server != null) server.stop(0);
    }

    private String start(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-sse");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                // 客户端超时关流后写回会抛 IOException，属于预期
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
    }

    private DeepSeekClient client(String url, int idleSeconds, int totalSeconds) {
        AppConfig config = new AppConfig(false,
                new AppConfig.Llm(5, 2, 1, 100, 100),
                new AppConfig.Deepseek(url, "deepseek-chat", "test-key", null, 1024),
                null, null, null, null, null);
        return new DeepSeekClient(config, new ObjectMapper(),
                new ModelConcurrencyLimiter(1, 1, 1), idleSeconds, totalSeconds);
    }

    private static List<AgentMessage> messages() {
        return List.of(AgentMessage.user("hi"));
    }

    private static void sse(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    @Test
    void errorBodiesAreBoundedFor400429And503() throws Exception {
        for (int code : List.of(400, 429, 503)) {
            release = new CountDownLatch(1);
            String url = start(exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("{\"error\":\"busy\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    release.await(8, TimeUnit.SECONDS);
                }
            });
            ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
            DeepSeekClient client = new DeepSeekClient(config(url), new ObjectMapper(), limiter, 1, 5);
            long started = System.currentTimeMillis();
            assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                    .isInstanceOf(AgentException.class)
                    .hasMessageContaining("错误响应读取超时");
            assertThat(System.currentTimeMillis() - started).isLessThan(5000);
            assertThat(limiter.availableTotal()).isEqualTo(1);
            release.countDown();
            server.stop(0);
            server = null;
        }
    }

    @Test
    void errorHeadersWithoutBodyStillTimeOut() throws Exception {
        String url = start(exchange -> {
            exchange.sendResponseHeaders(503, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.flush();                 // 只把响应头发出去,正文一个字节都不写
                Thread.sleep(5000);
            }
        });
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
        DeepSeekClient client = new DeepSeekClient(config(url), new ObjectMapper(), limiter, 1, 5);
        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("错误响应读取超时");
        assertThat(System.currentTimeMillis() - started).isLessThan(5000);
        assertThat(limiter.availableTotal()).isEqualTo(1);
    }

    @Test
    void errorBodyWithoutNewlineStillTimesOut() throws Exception {
        String url = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{\"error\":".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(5000);
            }
        });
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
        DeepSeekClient client = new DeepSeekClient(config(url), new ObjectMapper(), limiter, 1, 5);
        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("错误响应读取超时");
        assertThat(System.currentTimeMillis() - started).isLessThan(5000);
        assertThat(limiter.availableTotal()).isEqualTo(1);
    }

    @Test
    void repeatedErrorTimeoutsDoNotLeakPermitAndNextRequestReachesServer() throws Exception {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        String url = start(exchange -> {
            int n = hits.incrementAndGet();
            if (n <= 3) {
                exchange.sendResponseHeaders(503, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("{\"error\":\"busy\"}\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(3000);
                }
                return;
            }
            sse(exchange, data("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}") + data("[DONE]"));
        });
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
        DeepSeekClient client = new DeepSeekClient(config(url), new ObjectMapper(), limiter, 1, 5);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                    .isInstanceOf(AgentException.class);
            assertThat(limiter.availableTotal()).isEqualTo(1);
        }
        var turn = client.chatWithToolsStream(messages(), List.of(), s -> {});
        assertThat(turn.content()).isEqualTo("ok");
        assertThat(hits.get()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void firstTokenThenStallTimesOutAndReleasesPermit() throws Exception {
        release = new CountDownLatch(1);
        String url = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}").getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(8, TimeUnit.SECONDS);
                out.write(data("[DONE]").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
        DeepSeekClient client = new DeepSeekClient(config(url), new ObjectMapper(), limiter, 1, 5);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("空闲超时");
        assertThat(System.currentTimeMillis() - start).isLessThan(5000);
        assertThat(limiter.availableTotal()).isEqualTo(1);
    }

    @Test
    void heartbeatOnlyDoesNotResetIdleTimer() throws Exception {
        release = new CountDownLatch(1);
        String url = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 20; i++) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(200);
                }
            }
        });
        DeepSeekClient client = client(url, 1, 5);
        assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("空闲超时");
    }

    @Test
    void totalTimeoutWinsEvenWhenDataKeepsComing() throws Exception {
        release = new CountDownLatch(1);
        String url = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 20; i++) {
                    out.write(data("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(300);
                }
            }
        });
        DeepSeekClient client = client(url, 1, 2);
        assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("总时限");
    }

    @Test
    void normalDoneStillWorks() throws Exception {
        String url = start(exchange -> sse(exchange,
                data("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
                        + data("[DONE]")));
        DeepSeekClient client = client(url, 2, 5);
        var turn = client.chatWithToolsStream(messages(), List.of(), s -> {});
        assertThat(turn.content()).isEqualTo("ok");
    }

    @Test
    void upstreamClosesWithoutDoneDoesNotHang() throws Exception {
        String url = start(exchange -> sse(exchange,
                data("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")));
        DeepSeekClient client = client(url, 2, 5);
        var turn = client.chatWithToolsStream(messages(), List.of(), s -> {});
        assertThat(turn.content()).isEqualTo("ok");
    }

    @Test
    void upstreamErrorReleasesPermit() throws Exception {
        String url = start(exchange -> {
            byte[] body = "upstream down".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
        DeepSeekClient client = new DeepSeekClient(config(url), new ObjectMapper(), limiter, 2, 5);
        assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                .isInstanceOf(AgentException.class);
        assertThat(limiter.availableTotal()).isEqualTo(1);
    }

    @Test
    void repeatedTimeoutsReuseThePermit() throws Exception {
        String url = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data("{\"choices\":[{\"delta\":{\"content\":\"first\"}}]}").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(2000);
            }
        });
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
        DeepSeekClient client = new DeepSeekClient(config(url), new ObjectMapper(), limiter, 1, 5);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> {}))
                    .isInstanceOf(AgentException.class)
                    .hasMessageContaining("空闲超时");
            assertThat(limiter.availableTotal()).isEqualTo(1);
        }
    }

    @Test
    void cancellationStopsReadLoopAndReleasesPermit() throws Exception {
        String url = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 10; i++) {
                    out.write(data("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(100);
                }
            }
        });
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
        DeepSeekClient client = new DeepSeekClient(config(url), new ObjectMapper(), limiter, 5, 10);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        assertThatThrownBy(() -> client.chatWithToolsStream(messages(), List.of(), s -> cancelled.set(true), cancelled::get))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("取消");
        assertThat(limiter.availableTotal()).isEqualTo(1);
    }

    private AppConfig config(String url) {
        return new AppConfig(false,
                new AppConfig.Llm(5, 2, 1, 100, 100),
                new AppConfig.Deepseek(url, "deepseek-chat", "test-key", null, 1024),
                null, null, null, null, null);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
