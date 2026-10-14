package io.github.likeelysia.formalagent.llm;

import io.github.likeelysia.formalagent.exception.AgentException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 带「硬超时」的 HTTP 发送。
 *
 * <p><b>为什么不能只靠 {@link HttpRequest#timeout}</b>:
 * {@code java.net.http} 的请求超时只覆盖到「收到响应头」这一段;一旦连接进入「半死」状态,
 * 超时<b>不会触发</b> —— 调用会<b>永久挂死</b>。我们踩过的真实场景:
 * <b>笔记本合盖休眠</b> → 网络断、进程冻结 → 醒来后那条在飞的 socket 变僵尸,
 * 提炼/OCR 请求就再也不返回了(日志停住,CPU 为 0)。
 *
 * <p><b>这里的办法</b>:{@code sendAsync(...).orTimeout(...)} —— 给「连响应体一起收完」的整个过程
 * 上一个截止时间;到点抛 {@link TransientApiException}(可重试 / 记漏页),不再死等。
 *
 * <p>注意:超时后底层连接可能仍挂着(JDK 不保证取消),但调用方已经脱身 —— 对本项目来说够了。
 */
final class HttpCalls {

    private HttpCalls() {
    }

    /** 发请求并强制在 {@code timeoutSeconds} 秒内拿到完整响应;超时/网络故障 → 可重试异常。 */
    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                    HttpResponse.BodyHandler<T> handler, int timeoutSeconds) {
        int seconds = timeoutSeconds > 0 ? timeoutSeconds : 120;
        try {
            return client.sendAsync(request, handler)
                    .orTimeout(seconds, TimeUnit.SECONDS)
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                throw new TransientApiException(
                        "HTTP 请求超时(" + seconds + "s,连接可能已失效):" + request.uri(), cause);
            }
            if (cause instanceof IOException) {
                throw new TransientApiException("网络异常:" + cause.getMessage(), cause);
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new AgentException("HTTP 调用失败:" + cause, cause);
        }
    }
}
