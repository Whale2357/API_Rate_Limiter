package com.api_rate_limiter.ratelimiter;

import com.api_rate_limiter.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaRedisRateLimiterTest extends RedisTestSupport {

    private LuaRedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new LuaRedisRateLimiter(redisTemplate, new RateLimitLuaScript(), 60);
    }

    @Test
    void firstRequest_isAllowed_andRemainingDecrements() {
        RateLimitResult result = rateLimiter.tryAcquire("sk-userA");

        assertTrue(result.allowed());
        assertEquals(10, result.limit());
        assertEquals(9, result.remaining());
        assertEquals(0, result.retryAfterSeconds());
    }

    @Test
    void exceedingCapacity_isRejected_withRetryAfter() {
        String apiKey = "sk-burst-user";

        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimiter.tryAcquire(apiKey).allowed(), "request " + (i + 1) + " should be allowed");
        }

        RateLimitResult rejected = rateLimiter.tryAcquire(apiKey);
        assertFalse(rejected.allowed());
        assertEquals(0, rejected.remaining());
        assertEquals(1, rejected.retryAfterSeconds());
    }

    @Test
    void proTier_hasHigherCapacity() {
        RateLimitResult result = rateLimiter.tryAcquire("sk-pro-userB");

        assertTrue(result.allowed());
        assertEquals(50, result.limit());
        assertEquals(49, result.remaining());
    }

    @Test
    void enterpriseTier_hasHighestCapacity() {
        RateLimitResult result = rateLimiter.tryAcquire("sk-enterprise-userC");

        assertTrue(result.allowed());
        assertEquals(200, result.limit());
        assertEquals(199, result.remaining());
    }
}
