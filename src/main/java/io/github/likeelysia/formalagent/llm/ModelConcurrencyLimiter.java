package io.github.likeelysia.formalagent.llm;

import io.github.likeelysia.formalagent.exception.OverloadedException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 全应用共用的模型并发预算。
 *
 * <p>文字、图片、摘要、记忆提炼/对账最终都经过模型客户端，所以名额放在客户端调用层，
 * 而不是包在某一层业务循环外面 —— 后者漏掉图片识别、摘要和后台任务。
 *
 * <p>两层预算：
 * <ul>
 *   <li>{@code total}:所有模型 HTTP 请求合计最多几个(默认 2);</li>
 *   <li>{@code background}:其中后台提炼最多占几个(默认 1),保证至少一个名额留给交互。</li>
 * </ul>
 * 后台先拿 background 再拿 total,前台只拿 total;释放顺序反过来。这样不会形成
 * “后台拿着 total 等 background”的死锁。
 */
@Component
public class ModelConcurrencyLimiter {

    private final Semaphore total;
    private final Semaphore background;
    private final int timeoutSeconds;

    @Autowired
    public ModelConcurrencyLimiter(
            @Value("${fa.model.max-concurrent:2}") int maxConcurrent,
            @Value("${fa.model.background-concurrent:1}") int backgroundConcurrent,
            @Value("${fa.model.acquire-timeout-seconds:10}") int timeoutSeconds) {
        int max = Math.max(1, maxConcurrent);
        this.total = new Semaphore(max);
        this.background = new Semaphore(Math.max(1, Math.min(max, backgroundConcurrent)));
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    public Permit acquire() {
        boolean isBackground = ModelCallContext.isBackground();
        boolean backgroundHeld = false;
        boolean totalHeld = false;
        try {
            if (isBackground) {
                backgroundHeld = background.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
                if (!backgroundHeld) {
                    throw new OverloadedException("后台模型任务较多,请稍后再试");
                }
            }
            totalHeld = total.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
            if (!totalHeld) {
                throw new OverloadedException("当前模型请求较多,请稍后重试");
            }
            return new Permit(backgroundHeld);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OverloadedException("等待模型名额时被中断,请稍后重试");
        } finally {
            if (!totalHeld) {
                if (backgroundHeld) {
                    background.release();
                }
            }
        }
    }

    public int availableTotal() {
        return total.availablePermits();
    }

    public int availableBackground() {
        return background.availablePermits();
    }

    /** 一次模型请求的完整执行期(含流式读到底)。 */
    public final class Permit implements AutoCloseable {
        private final boolean backgroundHeld;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Permit(boolean backgroundHeld) {
            this.backgroundHeld = backgroundHeld;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            total.release();
            if (backgroundHeld) {
                background.release();
            }
        }
    }
}
