package com.api_rate_limiter;

import com.api_rate_limiter.domain.ReentrantLockTokenBucket;
import com.api_rate_limiter.domain.TokenBucket;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("benchmark")
class ReentrantLockFairnessBenchmarkTest {

    private static final int THREAD_COUNT = 100;
    private static final int REQUESTS_PER_THREAD = 1_000;
    private static final int TOTAL_REQUESTS = THREAD_COUNT * REQUESTS_PER_THREAD;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void benchmark_fairness_compareThroughput(boolean fair) throws InterruptedException {
        TokenBucket bucket = new ReentrantLockTokenBucket(TOTAL_REQUESTS, 0.0, fair);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
                        if (bucket.tryConsume()) {
                            allowedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long startNanos = System.nanoTime();
        startLatch.countDown();
        doneLatch.await();
        long elapsedNanos = System.nanoTime() - startNanos;
        executor.shutdown();

        double elapsedMs = elapsedNanos / 1_000_000.0;
        String label = fair ? "REENTRANT_LOCK (fair=true)" : "REENTRANT_LOCK (fair=false)";

        System.out.printf("[%s] total=%d, allowed=%d, elapsed=%.2f ms, throughput=%.0f req/s%n",
                label,
                TOTAL_REQUESTS,
                allowedCount.get(),
                elapsedMs,
                TOTAL_REQUESTS / (elapsedMs / 1000.0));

        assertEquals(TOTAL_REQUESTS, allowedCount.get());
    }
}
