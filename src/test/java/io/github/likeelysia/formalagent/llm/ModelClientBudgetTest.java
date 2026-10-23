package io.github.likeelysia.formalagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.likeelysia.formalagent.chat.Message;
import io.github.likeelysia.formalagent.config.AppConfig;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 用阻塞式假模型验证:真实客户端调用层的总/后台并发不超预算,失败也能归还名额。 */
class ModelClientBudgetTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void textModelCallsRespectSharedBudget() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch twoStarted = new CountDownLatch(2);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/chat/completions", exchange -> {
            int now = active.incrementAndGet();
            max.accumulateAndGet(now, Math::max);
            twoStarted.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } finally {
                active.decrementAndGet();
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";

        AppConfig config = new AppConfig(false,
                new AppConfig.Llm(5, 2, 1, 100, 100),
                new AppConfig.Deepseek(url, "deepseek-chat", "test-key", null, 1024),
                null, null, null, null, null);
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(2, 1, 2);
        DeepSeekClient client = new DeepSeekClient(config, new ObjectMapper(), limiter);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> client.chat(List.of(new Message("user", "hi")))));
        }
        assertThat(twoStarted.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);
        assertThat(max.get()).isLessThanOrEqualTo(2);
        release.countDown();
        for (var future : futures) future.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(limiter.availableTotal()).isEqualTo(2);
    }
}
