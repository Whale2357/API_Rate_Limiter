package com.api_rate_limiter.domain;

import com.api_rate_limiter.redis.RedisTokenBucketKeys;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V4-1: Redis Hash에서 GET(조회)과 SET(갱신)을 분리한 Check-Then-Act 구현.
 * 외부 저장소를 사용해도 명령 간 원자성이 없으면 분산 환경에서 V1과 동일한 레이스가 재발한다.
 */
public class NaiveRedisTokenBucket implements TokenBucket {

    private static final String FIELD_TOKENS = "tokens";
    private static final String FIELD_LAST_REFILL_TIME = "lastRefillTime";

    private final String userId;
    private final String redisKey;
    private final int capacity;
    private final double refillRate;
    private final StringRedisTemplate redisTemplate;
    private final long bucketTtlSeconds;

    public NaiveRedisTokenBucket(
            String userId,
            int capacity,
            double refillRate,
            StringRedisTemplate redisTemplate,
            long bucketTtlSeconds) {
        this.userId = userId;
        this.redisKey = RedisTokenBucketKeys.userBucket(userId);
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.redisTemplate = redisTemplate;
        this.bucketTtlSeconds = bucketTtlSeconds;
    }

    @Override
    public boolean tryConsume() {
        List<String> values = redisTemplate.<String, String>opsForHash()
                .multiGet(redisKey, List.of(FIELD_TOKENS, FIELD_LAST_REFILL_TIME));

        double tokens;
        long lastRefillTime;
        if (values.get(0) == null) {
            tokens = capacity;
            lastRefillTime = System.currentTimeMillis();
        } else {
            tokens = Double.parseDouble(values.get(0));
            lastRefillTime = Long.parseLong(values.get(1));
        }

        long now = System.currentTimeMillis();
        long elapsedMillis = Math.max(0, now - lastRefillTime);
        double tokensToAdd = (elapsedMillis / 1_000.0) * refillRate;
        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTime = now;
        }

        if (tokens < 1.0) {
            writeState(tokens, lastRefillTime);
            return false;
        }

        // 의도적 비원자 구간: 다른 서버/스레드도 동시에 tokens >= 1을 관측할 수 있다.
        tokens -= 1.0;
        writeState(tokens, lastRefillTime);
        return true;
    }

    private void writeState(double tokens, long lastRefillTime) {
        Map<String, String> state = new HashMap<>();
        state.put(FIELD_TOKENS, Double.toString(tokens));
        state.put(FIELD_LAST_REFILL_TIME, Long.toString(lastRefillTime));
        redisTemplate.opsForHash().putAll(redisKey, state);
        redisTemplate.expire(redisKey, java.time.Duration.ofSeconds(bucketTtlSeconds));
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
