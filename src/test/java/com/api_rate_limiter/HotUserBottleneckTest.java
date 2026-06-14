package com.api_rate_limiter;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.factory.TokenBucketFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("benchmark")
class HotUserBottleneckTest {

    private static final int THREAD_COUNT = 100;
    private static final int REQUESTS_PER_THREAD = 1_000;
    private static final int TOTAL_REQUESTS = THREAD_COUNT * REQUESTS_PER_THREAD;

    private record BenchmarkResult(long elapsedNanos, int allowedCount) {
        double elapsedMs() {
            return elapsedNanos / 1_000_000.0;
        }

        double throughput() {
            return TOTAL_REQUESTS / (elapsedMs() / 1000.0);
        }
    }

    private BenchmarkResult runHotUser(TokenBucketFactory factory) throws InterruptedException {
        TokenBucket hotBucket = factory.create(TOTAL_REQUESTS, 0.0);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < REQUESTS_PER_THREAD; j++) {
                        if (hotBucket.tryConsume()) {
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

        return new BenchmarkResult(elapsedNanos, allowedCount.get());
    }

    private BenchmarkResult runDistributed(TokenBucketFactory factory) throws InterruptedException {
        TokenBucket[] buckets = new TokenBucket[THREAD_COUNT];
        for (int i = 0; i < THREAD_COUNT; i++) {
            buckets[i] = factory.create(REQUESTS_PER_THREAD, 0.0);
        }

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TokenBucket bucket = buckets[threadIndex];
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

        return new BenchmarkResult(elapsedNanos, allowedCount.get());
    }

    @ParameterizedTest
    @EnumSource(value = RateLimiterStrategy.class, names = {"SYNCHRONIZED", "REENTRANT_LOCK"})
    void hotUser_vs_distributed_compareBottleneck(RateLimiterStrategy strategy) throws InterruptedException {
        TokenBucketFactory factory = new TokenBucketFactory(strategy);

        BenchmarkResult hotUser = runHotUser(factory);
        BenchmarkResult distributed = runDistributed(factory);

        double slowdown = hotUser.elapsedMs() / distributed.elapsedMs();

        System.out.printf("[%s] Hot User     -> elapsed=%.2f ms, throughput=%.0f req/s, allowed=%d%n",
                strategy, hotUser.elapsedMs(), hotUser.throughput(), hotUser.allowedCount());
        System.out.printf("[%s] Distributed  -> elapsed=%.2f ms, throughput=%.0f req/s, allowed=%d%n",
                strategy, distributed.elapsedMs(), distributed.throughput(), distributed.allowedCount());
        System.out.printf("[%s] Hot User is %.1fx slower than Distributed%n%n",
                strategy, slowdown);

        assertEquals(TOTAL_REQUESTS, hotUser.allowedCount());
        assertEquals(TOTAL_REQUESTS, distributed.allowedCount());
    }
}
