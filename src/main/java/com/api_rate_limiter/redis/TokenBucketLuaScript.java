package com.api_rate_limiter.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketLuaScript {

    private final DefaultRedisScript<Long> consumeScript;

    public TokenBucketLuaScript() {
        this.consumeScript = new DefaultRedisScript<>();
        this.consumeScript.setLocation(new ClassPathResource("scripts/token-bucket.lua"));
        this.consumeScript.setResultType(Long.class);
    }

    public DefaultRedisScript<Long> getConsumeScript() {
        return consumeScript;
    }
}
