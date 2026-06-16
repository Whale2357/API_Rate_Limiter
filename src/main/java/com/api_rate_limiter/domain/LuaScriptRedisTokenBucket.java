package com.api_rate_limiter.domain;

import com.api_rate_limiter.redis.RedisTokenBucketKeys;
import com.api_rate_limiter.redis.TokenBucketLuaScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * V4-2: Lua 스크립트로 refill + consume을 Redis 이벤트 루프 내에서 원자 실행한다.
 * CasTokenBucket의 BucketState + CAS 패턴을 분산 환경에 대응시킨 구현이다.
 */
public class LuaScriptRedisTokenBucket implements TokenBucket {

    private static final String FIELD_TOKENS = "tokens";

    private final String userId;
    private final String redisKey;
    private final int capacity;
    private final double refillRate;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> consumeScript;
    private final long bucketTtlSeconds;

    public LuaScriptRedisTokenBucket(
            String userId,
            int capacity,
            double refillRate,
            StringRedisTemplate redisTemplate,
            TokenBucketLuaScript tokenBucketLuaScript,
            long bucketTtlSeconds) {
        this.userId = userId;
        this.redisKey = RedisTokenBucketKeys.userBucket(userId);
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.redisTemplate = redisTemplate;
        this.consumeScript = tokenBucketLuaScript.getConsumeScript();
        this.bucketTtlSeconds = bucketTtlSeconds;
    }

    @Override
    public boolean tryConsume() {
        Long result = redisTemplate.execute(
                consumeScript,
                List.of(redisKey),
                Integer.toString(capacity),
                Double.toString(refillRate),
                Long.toString(bucketTtlSeconds));
        return result != null && result == 1L;
    }

    @Override
    public double getTokens() {
        String value = (String) redisTemplate.opsForHash().get(redisKey, FIELD_TOKENS);
        return value == null ? capacity : Double.parseDouble(value);
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public double getRefillRate() {
        return refillRate;
    }

    public String getUserId() {
        return userId;
    }
}
