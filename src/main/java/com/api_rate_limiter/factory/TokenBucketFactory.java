package com.api_rate_limiter.factory;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.domain.AbstractTokenBucket;
import com.api_rate_limiter.domain.LuaScriptRedisTokenBucket;
import com.api_rate_limiter.domain.NaiveRedisTokenBucket;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.ratelimiter.RateLimitLuaScript;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketFactory {

    private final RateLimiterStrategy strategy;
    private final StringRedisTemplate redisTemplate;
    private final RateLimitLuaScript rateLimitLuaScript;
    private final long bucketTtlSeconds;

    @Autowired
    public TokenBucketFactory(
            @Value("${rate-limiter.strategy:NO_LOCK}") RateLimiterStrategy strategy,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) RateLimitLuaScript rateLimitLuaScript,
            @Value("${rate-limiter.redis.bucket-ttl-seconds:3600}") long bucketTtlSeconds) {
        this.strategy = strategy;
        this.redisTemplate = redisTemplate;
        this.rateLimitLuaScript = rateLimitLuaScript;
        this.bucketTtlSeconds = bucketTtlSeconds;
    }

    private TokenBucketFactory(
            RateLimiterStrategy strategy,
            StringRedisTemplate redisTemplate,
            RateLimitLuaScript rateLimitLuaScript,
            long bucketTtlSeconds,
            boolean testOnly) {
        this.strategy = strategy;
        this.redisTemplate = redisTemplate;
        this.rateLimitLuaScript = rateLimitLuaScript;
        this.bucketTtlSeconds = bucketTtlSeconds;
    }

    public static TokenBucketFactory forStrategy(RateLimiterStrategy strategy) {
        return forStrategy(strategy, null);
    }

    public static TokenBucketFactory forStrategy(RateLimiterStrategy strategy, StringRedisTemplate redisTemplate) {
        RateLimitLuaScript luaScript = redisTemplate != null ? new RateLimitLuaScript() : null;
        return new TokenBucketFactory(strategy, redisTemplate, luaScript, 3600L, true);
    }

    public TokenBucket create(String userId) {
        return create(userId, AbstractTokenBucket.DEFAULT_CAPACITY, AbstractTokenBucket.DEFAULT_REFILL_RATE);
    }

    public TokenBucket create(int capacity, double refillRate) {
        return create("default", capacity, refillRate);
    }

    public TokenBucket create(String userId, int capacity, double refillRate) {
        if (strategy == RateLimiterStrategy.NAIVE_REDIS) {
            requireRedis();
            return new NaiveRedisTokenBucket(userId, capacity, refillRate, redisTemplate, bucketTtlSeconds);
        }
        if (strategy == RateLimiterStrategy.LUA_REDIS) {
            requireRedis();
            return new LuaScriptRedisTokenBucket(
                    userId, capacity, refillRate, redisTemplate, rateLimitLuaScript, bucketTtlSeconds);
        }
        return strategy.create(capacity, refillRate);
    }

    private void requireRedis() {
        if (redisTemplate == null) {
            throw new IllegalStateException(
                    strategy + " requires Redis — configure spring.data.redis.* and provide StringRedisTemplate");
        }
    }
}
