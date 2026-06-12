package com.api_rate_limiter.manager;

import com.api_rate_limiter.domain.TokenBucket;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenBucketManager {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public TokenBucket getOrCreate(String userId) {
        return buckets.computeIfAbsent(userId, id -> new TokenBucket());
    }
}
