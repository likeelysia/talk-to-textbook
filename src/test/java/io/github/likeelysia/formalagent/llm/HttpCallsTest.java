package io.github.likeelysia.formalagent.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * 方案 B:「硬超时」兜底。
 *
 * <p>服务器「接了但不回应」时(模拟合盖休眠后的僵尸连接),调用必须<b>到点抛异常</b>,
 * 而不是像以前那样永久挂死。
 */
class HttpCallsTest {

    @Test
    void timesOutWhenServerAcceptsButNeverResponds() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread holder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    Thread.sleep(5_000);          // 接了,但一个字都不回
                } catch (Exception ignored) {
                    // 测试收尾关闭 server 后会走到这
                }
            });
            holder.setDaemon(true);
            holder.start();

            HttpClient client = HttpClient.newBuilder().build();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.getLocalPort() + "/hang"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            long start = System.currentTimeMillis();
            assertThatThrownBy(() -> HttpCalls.send(client, request, HttpResponse.BodyHandlers.ofString(), 1))
                    .isInstanceOf(TransientApiException.class)
                    .hasMessageContaining("超时");
            long cost = System.currentTimeMillis() - start;

            // 1s 超时:不能"秒回"(说明根本没等),也不能拖太久(说明超时没生效)
            assertThat(cost).isBetween(900L, 8_000L);
        }
    }
}
