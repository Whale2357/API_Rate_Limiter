package com.api_rate_limiter.factory;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.domain.TokenBucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketFactory {

    private final RateLimiterStrategy strategy;

    public TokenBucketFactory(
            @Value("${rate-limiter.strategy:NO_LOCK}") RateLimiterStrategy strategy) {
        this.strategy = strategy;
    }

    public TokenBucket create() {
        return strategy.create();
    }

    public TokenBucket create(int capacity, double refillRate) {
        return strategy.create(capacity, refillRate);
    }

    public RateLimiterStrategy getStrategy() {
        return strategy;
    }
}
