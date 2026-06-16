package com.api_rate_limiter.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

public abstract class RedisTestSupport {

    protected static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUpRedis() {
        redisTemplate = RedisTestResource.connect();
    }

    @AfterEach
    void flushRedis() {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }
}
