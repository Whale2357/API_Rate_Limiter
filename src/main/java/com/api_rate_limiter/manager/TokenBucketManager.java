package com.api_rate_limiter.manager;

import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.factory.TokenBucketFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenBucketManager {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final TokenBucketFactory tokenBucketFactory;

    public TokenBucketManager(TokenBucketFactory tokenBucketFactory) {
        this.tokenBucketFactory = tokenBucketFactory;
    }

    public TokenBucket getOrCreate(String userId) {
        return buckets.computeIfAbsent(userId, tokenBucketFactory::create);
    }
}
