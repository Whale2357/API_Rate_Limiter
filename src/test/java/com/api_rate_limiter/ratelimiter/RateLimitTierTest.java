package com.api_rate_limiter.ratelimiter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitTierTest {

    @Test
    void defaultsToFreeForPlainKey() {
        assertEquals(RateLimitTier.FREE, RateLimitTier.fromApiKey("sk-userA"));
        assertEquals(10, RateLimitTier.FREE.getCapacity());
    }

    @Test
    void resolvesProPrefix() {
        assertEquals(RateLimitTier.PRO, RateLimitTier.fromApiKey("sk-pro-userB"));
        assertEquals(50, RateLimitTier.PRO.getCapacity());
    }

    @Test
    void resolvesEnterprisePrefix() {
        assertEquals(RateLimitTier.ENTERPRISE, RateLimitTier.fromApiKey("sk-enterprise-userC"));
        assertEquals(200, RateLimitTier.ENTERPRISE.getCapacity());
    }

    @Test
    void nullKeyDefaultsToFree() {
        assertEquals(RateLimitTier.FREE, RateLimitTier.fromApiKey(null));
    }
}
