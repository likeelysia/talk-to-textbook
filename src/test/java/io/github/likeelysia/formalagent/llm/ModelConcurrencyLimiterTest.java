package io.github.likeelysia.formalagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 共享模型预算:总并发不超上限,后台不超后台名额,异常也能归还。 */
class ModelConcurrencyLimiterTest {

    @Test
    void totalConcurrencyIsBounded() throws Exception {
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(2, 1, 2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    try (ModelConcurrencyLimiter.Permit ignored = limiter.acquire()) {
                        int now = active.incrementAndGet();
                        max.accumulateAndGet(now, Math::max);
                        Thread.sleep(120);
                        active.decrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(max.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void backgroundConcurrencyIsOneAndBackgroundScopeIsRestored() throws Exception {
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(2, 1, 2);
        AtomicInteger backgroundActive = new AtomicInteger();
        AtomicInteger backgroundMax = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    try (ModelCallContext.Scope scope = ModelCallContext.background();
                         ModelConcurrencyLimiter.Permit ignored = limiter.acquire()) {
                        int now = backgroundActive.incrementAndGet();
                        backgroundMax.accumulateAndGet(now, Math::max);
                        Thread.sleep(120);
                        backgroundActive.decrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(backgroundMax.get()).isLessThanOrEqualTo(1);
        assertThat(ModelCallContext.isBackground()).isFalse();
    }

    @Test
    void permitsAreReleasedWhenCallFails() {
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter(1, 1, 1);
        try {
            try (ModelConcurrencyLimiter.Permit ignored = limiter.acquire()) {
                throw new IllegalStateException("boom");
            }
        } catch (IllegalStateException ignored) {
            // 预期
        }
        assertThat(limiter.availableTotal()).isEqualTo(1);
    }
}
