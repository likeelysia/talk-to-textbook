package io.github.likeelysia.formalagent.memory;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 全应用共享的记忆后台执行器：有界队列，队列满直接拒绝，不阻塞前台、不使用 CallerRunsPolicy。
 *
 * <p>模型请求的真正并发仍由既有的 ModelConcurrencyLimiter 控制；这里只限制待执行任务积压。
 * 单用户的 LongTermMemory 不能关闭这个池，池由 Spring 容器统一关闭。
 */
@Component
public class MemoryTaskExecutor implements Executor, AutoCloseable {

    private final ThreadPoolExecutor pool;

    public MemoryTaskExecutor(
            @Value("${fa.memory.executor.threads:1}") int threads,
            @Value("${fa.memory.executor.queue-capacity:32}") int queueCapacity) {
        int n = Math.max(1, threads);
        int q = Math.max(1, queueCapacity);
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(q),
                r -> {
                    Thread t = new Thread(r, "memory-worker-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) throw new RejectedExecutionException("task is null");
        pool.execute(command);
    }

    /** 队列满时抛 RejectedExecutionException，由调用方释放 inflight/backfill 且不推进水位。 */
    public boolean submit(Runnable command) {
        try {
            execute(command);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    public int queued() {
        return pool.getQueue().size();
    }

    public int active() {
        return pool.getActiveCount();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
