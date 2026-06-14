package com.api_rate_limiter;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.factory.TokenBucketFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyRaceConditionTest {

    private static final int THREAD_COUNT = 500;
    private static final int DEMO_ATTEMPTS = 50;

    private int runConcurrentConsume(TokenBucket bucket) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Thread.onSpinWait();
                    if (bucket.tryConsume()) {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        return allowedCount.get();
    }

    @Test
    @Tag("v2-demo")
    void v1_raceCondition_capacityOne_exceedsAllowedLimit() throws InterruptedException {
        int maxAllowed = 0;

        for (int attempt = 0; attempt < DEMO_ATTEMPTS; attempt++) {
            TokenBucket bucket = new TokenBucketFactory(RateLimiterStrategy.NO_LOCK).create(1, 0.0);
            int allowedCount = runConcurrentConsume(bucket);
            maxAllowed = Math.max(maxAllowed, allowedCount);
        }

        System.out.printf(
                "[V2-1 DEMO] V1 (No Lock) - max allowed across %d attempts: %d (expected 1, race condition reproduced)%n",
                DEMO_ATTEMPTS,
                maxAllowed);

        assertTrue(maxAllowed > 1,
                "Race condition should allow more than 1 request when capacity=1 under concurrent access. "
                        + "This test intentionally fails (RED) until synchronization is applied.");
    }

    @ParameterizedTest
    @EnumSource(value = RateLimiterStrategy.class, names = {"SYNCHRONIZED", "REENTRANT_LOCK"})
    void synchronizedStrategies_capacityOne_onlyOneAllowed(RateLimiterStrategy strategy)
            throws InterruptedException {
        TokenBucket bucket = new TokenBucketFactory(strategy).create(1, 0.0);

        int allowedCount = runConcurrentConsume(bucket);

        System.out.printf("[%s] allowed=%d%n", strategy, allowedCount);
        assertEquals(1, allowedCount,
                strategy + " should allow exactly 1 request when capacity=1 under concurrent access");
    }
}
