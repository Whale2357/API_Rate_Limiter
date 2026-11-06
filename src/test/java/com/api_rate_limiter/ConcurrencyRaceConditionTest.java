package com.api_rate_limiter;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.factory.TokenBucketFactory;
import com.api_rate_limiter.support.ConcurrentRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyRaceConditionTest {

    private static final int THREAD_COUNT = 500;
    private static final int DEMO_ATTEMPTS = 50;

    private int runConcurrentConsume(TokenBucket bucket) throws InterruptedException {
        return ConcurrentRunner.run(THREAD_COUNT, 1, bucket).allowedCount();
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
                "Race condition was not reproduced in " + DEMO_ATTEMPTS + " attempts (max allowed=" + maxAllowed
                        + "). Reproduction is timing-dependent; re-run or increase DEMO_ATTEMPTS/THREAD_COUNT.");
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
