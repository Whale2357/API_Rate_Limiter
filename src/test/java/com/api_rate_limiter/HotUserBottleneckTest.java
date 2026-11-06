package com.api_rate_limiter;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.factory.TokenBucketFactory;
import com.api_rate_limiter.support.ConcurrentRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("benchmark")
class HotUserBottleneckTest {

    private static final int THREAD_COUNT = 100;
    private static final int REQUESTS_PER_THREAD = 1_000;
    private static final int TOTAL_REQUESTS = THREAD_COUNT * REQUESTS_PER_THREAD;

    private ConcurrentRunner.Result runHotUser(TokenBucketFactory factory) throws InterruptedException {
        TokenBucket hotBucket = factory.create(TOTAL_REQUESTS, 0.0);
        return ConcurrentRunner.run(THREAD_COUNT, REQUESTS_PER_THREAD, hotBucket);
    }

    private ConcurrentRunner.Result runDistributed(TokenBucketFactory factory) throws InterruptedException {
        TokenBucket[] buckets = new TokenBucket[THREAD_COUNT];
        for (int i = 0; i < THREAD_COUNT; i++) {
            buckets[i] = factory.create(REQUESTS_PER_THREAD, 0.0);
        }
        return ConcurrentRunner.run(THREAD_COUNT, REQUESTS_PER_THREAD, threadIndex -> buckets[threadIndex]);
    }

    @ParameterizedTest
    @EnumSource(value = RateLimiterStrategy.class, names = {"SYNCHRONIZED", "REENTRANT_LOCK"})
    void hotUser_vs_distributed_compareBottleneck(RateLimiterStrategy strategy) throws InterruptedException {
        TokenBucketFactory factory = new TokenBucketFactory(strategy);

        ConcurrentRunner.Result hotUser = runHotUser(factory);
        ConcurrentRunner.Result distributed = runDistributed(factory);

        double slowdown = hotUser.elapsedMs() / distributed.elapsedMs();

        System.out.printf("[%s] Hot User     -> elapsed=%.2f ms, throughput=%.0f req/s, allowed=%d%n",
                strategy, hotUser.elapsedMs(), hotUser.throughput(TOTAL_REQUESTS), hotUser.allowedCount());
        System.out.printf("[%s] Distributed  -> elapsed=%.2f ms, throughput=%.0f req/s, allowed=%d%n",
                strategy, distributed.elapsedMs(), distributed.throughput(TOTAL_REQUESTS), distributed.allowedCount());
        System.out.printf("[%s] Hot User is %.1fx slower than Distributed%n%n",
                strategy, slowdown);

        assertEquals(TOTAL_REQUESTS, hotUser.allowedCount());
        assertEquals(TOTAL_REQUESTS, distributed.allowedCount());
    }
}
