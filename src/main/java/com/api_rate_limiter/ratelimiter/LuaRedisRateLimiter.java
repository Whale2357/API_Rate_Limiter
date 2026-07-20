package com.api_rate_limiter.ratelimiter;

import com.api_rate_limiter.redis.RedisTokenBucketKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V5: V4에서 완성한 Redis Lua 토큰 버킷 엔진을 API Key 기반 게이트웨이용으로 노출한다.
 * 등급(Tier)별 정책을 적용하고, 표준 헤더 구성을 위한 잔여 토큰을 함께 반환한다.
 */
@Component
public class LuaRedisRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final RateLimitLuaScript rateLimitLuaScript;
    private final long bucketTtlSeconds;

    public LuaRedisRateLimiter(
            StringRedisTemplate redisTemplate,
            RateLimitLuaScript rateLimitLuaScript,
            @Value("${rate-limiter.api.bucket-ttl-seconds:60}") long bucketTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.rateLimitLuaScript = rateLimitLuaScript;
        this.bucketTtlSeconds = bucketTtlSeconds;
    }

    public RateLimitResult tryAcquire(String apiKey) {
        RateLimitTier tier = RateLimitTier.fromApiKey(apiKey);
        String key = RedisTokenBucketKeys.apiKeyBucket(apiKey);

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                rateLimitLuaScript.getScript(),
                List.of(key),
                Integer.toString(tier.getCapacity()),
                Double.toString(tier.getRefillRate()),
                Long.toString(bucketTtlSeconds));

        boolean allowed = result != null && !result.isEmpty() && result.get(0) == 1L;
        long remaining = (result != null && result.size() > 1) ? result.get(1) : 0L;
        int retryAfterSeconds = (result != null && result.size() > 2) ? result.get(2).intValue() : 0;

        return new RateLimitResult(allowed, tier.getCapacity(), remaining, retryAfterSeconds);
    }
}
