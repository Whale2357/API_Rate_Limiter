package com.api_rate_limiter;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.factory.TokenBucketFactory;
import com.api_rate_limiter.support.ConcurrentRunner;
import com.api_rate_limiter.support.RedisTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("benchmark")
class DistributedConcurrencyBenchmarkTest extends RedisTestSupport {

    private static final int THREAD_COUNT = 100;
    private static final int REQUESTS_PER_THREAD = 100;
    private static final int TOTAL_REQUESTS = THREAD_COUNT * REQUESTS_PER_THREAD;

    @ParameterizedTest
    @EnumSource(value = RateLimiterStrategy.class, names = {"NAIVE_REDIS", "LUA_REDIS"})
    void benchmark_redisStrategies_measureThroughput(RateLimiterStrategy strategy) throws InterruptedException {
        TokenBucketFactory factory = TokenBucketFactory.forStrategy(strategy, redisTemplate);
        TokenBucket bucket = factory.create("benchmark-user", TOTAL_REQUESTS, 0.0);

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREAD_COUNT, REQUESTS_PER_THREAD, bucket);

        System.out.printf(
                "[%s] total=%d, allowed=%d, elapsed=%.2f ms, throughput=%.0f req/s%n",
                strategy,
                TOTAL_REQUESTS,
                result.allowedCount(),
                result.elapsedMs(),
                result.throughput(TOTAL_REQUESTS));

        if (strategy == RateLimiterStrategy.NAIVE_REDIS) {
            System.out.printf("[%s] allowed=%d (data corruption expected under contention)%n",
                    strategy, result.allowedCount());
        } else {
            assertEquals(TOTAL_REQUESTS, result.allowedCount(),
                    strategy + " should consume all tokens exactly once under concurrent access");
        }
    }
}
