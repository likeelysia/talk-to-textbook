package io.github.likeelysia.formalagent.web;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 的"小工具间":把「跑一段阻塞工作 + 定期心跳 + 出错兜底 + 收尾」这套固定动作收在一处。
 *
 * <p>为什么需要它:
 * <ol>
 *   <li><b>不能占用 Tomcat 请求线程</b> —— 大模型一次回答几十秒,拿请求线程干等会把整个容器拖垮,
 *       所以控制器立刻把 emitter 返回给 Spring,真正的活儿扔到自己的线程池里跑;</li>
 *   <li><b>得定期说话</b> —— 工具检索、图片识别可能几十秒一个字都不吐,中间的代理/网关会把
 *       "看起来已经死掉"的连接掐断。每 {@value #PING_INTERVAL_MS}ms 发一条 SSE 注释当心跳;</li>
 *   <li><b>错误得有人接</b> —— 工作线程里抛异常如果没人接,请求会一直挂着不结束,浏览器那边
 *       永远停在"正在思考…"。这里统一兜成一条 error 事件再收尾。</li>
 * </ol>
 */
@Component
public class SseStreamer {

    private static final Logger log = LoggerFactory.getLogger(SseStreamer.class);

    /** 心跳间隔(毫秒)。 */
    public static final long PING_INTERVAL_MS = 15_000;

    private final ExecutorService workers;
    private final ScheduledExecutorService pinger;

    public SseStreamer() {
        this.workers = Executors.newCachedThreadPool(daemonThreads("agent-stream-"));
        this.pinger = Executors.newSingleThreadScheduledExecutor(daemonThreads("sse-ping-"));
    }

    /**
     * 启动一段流式工作:{@code work} 在独立线程上跑,期间每 15 秒一条心跳;
     * 它抛出的任何异常都变成一条 {@code error} 事件,最后无论成败都收尾。
     */
    public void start(SseEmitter emitter, Consumer<SseEmitter> work) {
        ScheduledFuture<?> heartbeat = pinger.scheduleAtFixedRate(
                () -> quiet(() -> emitter.send(SseEmitter.event().comment("ping"))),
                PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);

        workers.execute(() -> {
            try {
                work.accept(emitter);
            } catch (StreamClosedException e) {
                log.debug("客户端已断开连接,停止推送");                 // 刷新页面/点停止都会走到这
            } catch (Exception e) {
                log.warn("流式问答中断:{}", e.getMessage());
                quiet(() -> sendEvent(emitter, "error",
                        Map.of("message", e.getMessage() == null ? "服务端异常" : e.getMessage())));
            } finally {
                heartbeat.cancel(false);
                quiet(emitter::complete);
            }
        });
    }

    /**
     * 发一条命名事件,数据对象会被序列化成 JSON 放在 {@code data:} 里。
     *
     * <p>连接已经断了就抛 {@link StreamClosedException} —— 这是"正常结束",不是故障,
     * 让上层立刻停手,省得白算下去。
     */
    public static void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new StreamClosedException(e);
        }
    }

    /** 客户端断了 / emitter 已收尾:静默跳过,不刷日志也不抛。 */
    private static void quiet(Sender action) {
        try {
            action.run();
        } catch (RuntimeException | IOException ignored) {
            // 连接没了而已:刷新页面、点"停止"、网络抖动都会这样,没必要报错
        }
    }

    /** 允许抛 IOException 的动作 —— 这样 "发事件" 和 "收尾" 能共用同一个安静执行器。 */
    @FunctionalInterface
    private interface Sender {
        void run() throws IOException;
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(true);                       // 守护线程:不拦着 JVM 退出
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        pinger.shutdownNow();
    }

    /** 连接已断(或 emitter 已收尾)—— 用来把"正常结束"和"真故障"分开。 */
    public static class StreamClosedException extends RuntimeException {
        public StreamClosedException(Throwable cause) {
            super("客户端连接已断开", cause);
        }
    }
}
