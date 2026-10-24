package io.github.likeelysia.formalagent.llm;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 给响应体加“总时限 + 空闲时限”。
 *
 * <p>HttpRequest.timeout 只管到拿到响应头;响应体是惰性读取的,模型吐完第一个 token
 * 后停住时,原实现会一直占着模型名额。这里用看门狗在超时后真正关闭响应体,
 * 让阻塞的读循环退出;名额由外层 try-with-resources 在读循环结束后释放,
 * 不会出现“名额先还了、后台还在读”的假并发。
 *
 * <p>成功 SSE 的空闲只看 data: 事件,不看 ": ping" 心跳;错误响应体按“任意一段
 * 错误正文”作为活动。关闭对象只要求是 AutoCloseable,成功流和错误流都用同一个看门狗。
 */
final class StreamReadGuard implements AutoCloseable {

    private final AutoCloseable body;
    private final long idleMillis;
    private final long totalMillis;
    private final long totalDeadline;
    private final String idleLabel;
    private final ScheduledFuture<?> task;
    private final AtomicBoolean timedOut = new AtomicBoolean(false);
    private final AtomicReference<String> reason = new AtomicReference<>("");
    private final AtomicLong lastDataAt = new AtomicLong();

    StreamReadGuard(AutoCloseable body, long startedAt, long idleMillis, long totalMillis,
                    ScheduledExecutorService scheduler) {
        this(body, startedAt, idleMillis, totalMillis, scheduler, "data 事件");
    }

    StreamReadGuard(AutoCloseable body, long startedAt, long idleMillis, long totalMillis,
                    ScheduledExecutorService scheduler, String idleLabel) {
        this.body = body;
        this.idleMillis = Math.max(100, idleMillis);
        this.totalMillis = Math.max(100, totalMillis);
        this.totalDeadline = startedAt + this.totalMillis;
        this.idleLabel = idleLabel;
        this.lastDataAt.set(System.currentTimeMillis());
        long interval = Math.max(50, Math.min(1000, Math.min(this.idleMillis, totalMillis) / 4));
        this.task = scheduler.scheduleAtFixedRate(this::check, interval, interval, TimeUnit.MILLISECONDS);
    }

    /** 每收到一个 data: 行调用一次;心跳注释不调用。 */
    void touchData() {
        touch();
    }

    /** 错误响应体每读到一段字节调用一次。 */
    void touch() {
        lastDataAt.set(System.currentTimeMillis());
    }

    boolean isTimedOut() {
        return timedOut.get();
    }

    String reason() {
        String value = reason.get();
        return value == null || value.isBlank() ? "流式响应超时" : value;
    }

    private void check() {
        if (timedOut.get()) return;
        long now = System.currentTimeMillis();
        if (now >= totalDeadline) {
            timeout("流式响应超过总时限(" + (totalMillis / 1000) + " 秒)");
        } else if (now - lastDataAt.get() >= idleMillis) {
            timeout("流式响应空闲超时(" + (idleMillis / 1000) + " 秒没有收到 " + idleLabel + ")");
        }
    }

    private void timeout(String message) {
        if (!timedOut.compareAndSet(false, true)) return;
        reason.set(message);
        closeBody();
    }

    private void closeBody() {
        try {
            body.close();
        } catch (Exception ignored) {
            // 看门狗关闭响应体是尽力而为;真正的退出由读循环的异常/结束驱动
        }
    }

    @Override
    public void close() {
        task.cancel(false);
        closeBody();
    }

    static ScheduledExecutorService daemonScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "stream-read-guard");
            thread.setDaemon(true);
            return thread;
        });
    }
}
