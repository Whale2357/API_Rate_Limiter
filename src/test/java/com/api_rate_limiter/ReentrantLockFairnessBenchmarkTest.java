package com.api_rate_limiter;

import com.api_rate_limiter.domain.ReentrantLockTokenBucket;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.support.ConcurrentRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

        ConcurrentRunner.Result result = ConcurrentRunner.run(THREAD_COUNT, REQUESTS_PER_THREAD, bucket);

        String label = fair ? "REENTRANT_LOCK (fair=true)" : "REENTRANT_LOCK (fair=false)";

        System.out.printf("[%s] total=%d, allowed=%d, elapsed=%.2f ms, throughput=%.0f req/s%n",
                label,
                TOTAL_REQUESTS,
                result.allowedCount(),
                result.elapsedMs(),
                result.throughput(TOTAL_REQUESTS));

        assertEquals(TOTAL_REQUESTS, result.allowedCount());
    }
}
