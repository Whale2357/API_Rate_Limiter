package com.api_rate_limiter.domain;

import com.api_rate_limiter.ratelimiter.RateLimitLuaScript;
import com.api_rate_limiter.redis.RedisTokenBucketKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * V4-2: Lua 스크립트로 refill + consume을 Redis 이벤트 루프 내에서 원자 실행한다.
 * CasTokenBucket의 BucketState + CAS 패턴을 분산 환경에 대응시킨 구현이다.
 * V5 게이트웨이(LuaRedisRateLimiter)와 같은 rate-limiter.lua를 공유하며, 반환값 중 허용 여부만 사용한다.
 */
public class LuaScriptRedisTokenBucket implements TokenBucket {

    private final String redisKey;
    private final int capacity;
    private final double refillRate;
    private final StringRedisTemplate redisTemplate;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;
    private final long bucketTtlSeconds;

    public LuaScriptRedisTokenBucket(
            String userId,
            int capacity,
            double refillRate,
            StringRedisTemplate redisTemplate,
            RateLimitLuaScript rateLimitLuaScript,
            long bucketTtlSeconds) {
        this.redisKey = RedisTokenBucketKeys.userBucket(userId);
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.redisTemplate = redisTemplate;
        this.script = rateLimitLuaScript.getScript();
        this.bucketTtlSeconds = bucketTtlSeconds;
    }

    @Override
    public boolean tryConsume() {
        List<?> result = redisTemplate.execute(
                script,
                List.of(redisKey),
                Integer.toString(capacity),
                Double.toString(refillRate),
                Long.toString(bucketTtlSeconds));
        return result != null && !result.isEmpty() && Long.valueOf(1L).equals(result.get(0));
    }
}
