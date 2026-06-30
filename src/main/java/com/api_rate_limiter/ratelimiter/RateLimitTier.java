package com.api_rate_limiter.ratelimiter;

/**
 * V5: API Key 프리픽스로 분기되는 등급별 정책.
 * 향후 DB 연동으로 확장할 수 있도록 정책 결정을 한 곳에 모았다.
 */
public enum RateLimitTier {

    FREE(10, 10.0),
    PRO(50, 50.0),
    ENTERPRISE(200, 200.0);

    private final int capacity;
    private final double refillRate;

    RateLimitTier(int capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
    }

    public int getCapacity() {
        return capacity;
    }

    public double getRefillRate() {
        return refillRate;
    }

    public static RateLimitTier fromApiKey(String apiKey) {
        if (apiKey == null) {
            return FREE;
        }
        if (apiKey.startsWith("sk-enterprise-")) {
            return ENTERPRISE;
        }
        if (apiKey.startsWith("sk-pro-")) {
            return PRO;
        }
        return FREE;
    }
}
