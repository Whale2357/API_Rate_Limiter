package com.api_rate_limiter.service;

import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.manager.TokenBucketManager;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterService {

    private final TokenBucketManager tokenBucketManager;

    public RateLimiterService(TokenBucketManager tokenBucketManager) {
        this.tokenBucketManager = tokenBucketManager;
    }

    public boolean tryAcquire(String userId) {
        TokenBucket bucket = tokenBucketManager.getOrCreate(userId);
        return bucket.tryConsume();
    }
}
