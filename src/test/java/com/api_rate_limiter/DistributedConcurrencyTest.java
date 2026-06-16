package com.api_rate_limiter;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.factory.TokenBucketFactory;
import com.api_rate_limiter.support.RedisTestSupport;
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

class DistributedConcurrencyTest extends RedisTestSupport {

    private static final int THREAD_COUNT = 500;
    private static final int DEMO_ATTEMPTS = 50;
    private static final String USER_ID = "distributed-user";

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

    private int runMultiInstanceConsume(TokenBucket serverA, TokenBucket serverB) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger allowedCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            TokenBucket bucket = (i % 2 == 0) ? serverA : serverB;
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
    @Tag("v4-demo")
    void v4_naiveRedis_capacityOne_exceedsAllowedLimit() throws InterruptedException {
        int maxAllowed = 0;
        TokenBucketFactory factory = TokenBucketFactory.forStrategy(RateLimiterStrategy.NAIVE_REDIS, redisTemplate);

        for (int attempt = 0; attempt < DEMO_ATTEMPTS; attempt++) {
            TokenBucket bucket = factory.create(USER_ID + "-naive-" + attempt, 1, 0.0);
            int allowedCount = runConcurrentConsume(bucket);
            maxAllowed = Math.max(maxAllowed, allowedCount);
        }

        System.out.printf(
                "[V4-1 DEMO] NAIVE_REDIS - max allowed across %d attempts: %d (expected 1, distributed check-then-act race reproduced)%n",
                DEMO_ATTEMPTS,
                maxAllowed);

        assertTrue(maxAllowed > 1,
                "Naive Redis should allow more than 1 request when capacity=1 under concurrent access. "
                        + "GET and SET are not atomic across distributed servers.");
    }

    @Test
    void v4_naiveRedis_twoInstances_capacityOne_exceedsAllowedLimit() throws InterruptedException {
        TokenBucketFactory factory = TokenBucketFactory.forStrategy(RateLimiterStrategy.NAIVE_REDIS, redisTemplate);
        TokenBucket serverA = factory.create(USER_ID + "-naive-multi", 1, 0.0);
        TokenBucket serverB = factory.create(USER_ID + "-naive-multi", 1, 0.0);

        int allowedCount = runMultiInstanceConsume(serverA, serverB);

        System.out.printf(
                "[V4-1] NAIVE_REDIS (2 server wrappers) - allowed=%d (expected > 1)%n",
                allowedCount);

        assertTrue(allowedCount > 1,
                "Naive Redis with two server instances should exceed capacity=1 under concurrent access");
    }

    @ParameterizedTest
    @EnumSource(value = RateLimiterStrategy.class, names = {"LUA_REDIS"})
    void v4_luaRedis_capacityOne_onlyOneAllowed(RateLimiterStrategy strategy) throws InterruptedException {
        TokenBucketFactory factory = TokenBucketFactory.forStrategy(strategy, redisTemplate);
        TokenBucket bucket = factory.create(USER_ID + "-lua-single", 1, 0.0);

        int allowedCount = runConcurrentConsume(bucket);

        System.out.printf("[%s] allowed=%d%n", strategy, allowedCount);
        assertEquals(1, allowedCount,
                strategy + " should allow exactly 1 request when capacity=1 under concurrent access");
    }

    @Test
    void v4_luaRedis_twoInstances_capacityOne_onlyOneAllowed() throws InterruptedException {
        TokenBucketFactory factory = TokenBucketFactory.forStrategy(RateLimiterStrategy.LUA_REDIS, redisTemplate);
        TokenBucket serverA = factory.create(USER_ID + "-lua-multi", 1, 0.0);
        TokenBucket serverB = factory.create(USER_ID + "-lua-multi", 1, 0.0);

        int allowedCount = runMultiInstanceConsume(serverA, serverB);

        System.out.printf(
                "[V4-2] LUA_REDIS (2 server wrappers) - allowed=%d (expected exactly 1)%n",
                allowedCount);

        assertEquals(1, allowedCount,
                "Lua Redis should allow exactly 1 request across two server instances when capacity=1");
    }
}
