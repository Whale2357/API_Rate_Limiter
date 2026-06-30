package com.api_rate_limiter.ratelimiter;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * V5: 허용 여부와 잔여 토큰을 배열로 반환하는 Lua 스크립트 로더.
 */
@Component
public class RateLimitLuaScript {

    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;

    @SuppressWarnings("rawtypes")
    public RateLimitLuaScript() {
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/rate-limiter.lua"));
        this.script.setResultType(List.class);
    }

    @SuppressWarnings("rawtypes")
    public DefaultRedisScript<List> getScript() {
        return script;
    }
}
