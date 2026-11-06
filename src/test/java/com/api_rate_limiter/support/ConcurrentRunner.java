package com.api_rate_limiter.support;

import com.api_rate_limiter.domain.TokenBucket;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ConcurrentRunner {

    private static final long TIMEOUT_SECONDS = 60;

    private ConcurrentRunner() {
    }

    public record Result(int allowedCount, long elapsedNanos) {

        public double elapsedMs() {
            return elapsedNanos / 1_000_000.0;
        }

        public double throughput(int totalRequests) {
            return totalRequests / (elapsedMs() / 1000.0);
        }
    }

    public static Result run(int threadCount, int requestsPerThread, TokenBucket sharedBucket)
            throws InterruptedException {
        return run(threadCount, requestsPerThread, threadIndex -> sharedBucket);
    }

    /**
     * 모든 스레드를 startLatch로 동시에 출발시키고, 스레드마다 bucketForThread가 돌려준 버킷에
     * requestsPerThread번 tryConsume()을 호출한다.
     */
    public static Result run(int threadCount, int requestsPerThread, IntFunction<TokenBucket> bucketForThread)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger allowedCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < threadCount; i++) {
                TokenBucket bucket = bucketForThread.apply(i);
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Thread.onSpinWait();
                        for (int j = 0; j < requestsPerThread; j++) {
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
            assertTrue(doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Concurrent run did not finish within " + TIMEOUT_SECONDS + "s (possible deadlock or hang)");
            long elapsedNanos = System.nanoTime() - startNanos;

            return new Result(allowedCount.get(), elapsedNanos);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
