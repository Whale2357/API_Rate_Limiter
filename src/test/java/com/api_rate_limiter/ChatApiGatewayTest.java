package com.api_rate_limiter;

import com.api_rate_limiter.controller.ChatController;
import com.api_rate_limiter.filter.ApiKeyFilter;
import com.api_rate_limiter.interceptor.RateLimitInterceptor;
import com.api_rate_limiter.ratelimiter.LuaRedisRateLimiter;
import com.api_rate_limiter.ratelimiter.RateLimitLuaScript;
import com.api_rate_limiter.redis.RedisTokenBucketKeys;
import com.api_rate_limiter.service.AiService;
import com.api_rate_limiter.support.RedisTestSupport;
import com.api_rate_limiter.web.ErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V5: ApiKeyFilter → RateLimitInterceptor → ChatController 게이트웨이 파이프라인 통합 검증.
 */
class ChatApiGatewayTest extends RedisTestSupport {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LuaRedisRateLimiter rateLimiter =
                new LuaRedisRateLimiter(redisTemplate, new RateLimitLuaScript(), 60);
        ErrorResponseWriter errorResponseWriter = new ErrorResponseWriter(JsonMapper.builder().build());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(new AiService()))
                .addFilters(new ApiKeyFilter(errorResponseWriter))
                .addMappedInterceptors(new String[]{"/v1/**"}, new RateLimitInterceptor(rateLimiter, errorResponseWriter))
                .build();
    }

    @Test
    void missingAuthorization_returns401() throws Exception {
        mockMvc.perform(post("/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void allowedRequest_returns200_withStandardHeaders() throws Exception {
        mockMvc.perform(post("/v1/chat")
                        .header("Authorization", "Bearer sk-userA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Mock AI Response"))
                .andExpect(header().string("X-RateLimit-Limit", "10"))
                .andExpect(header().string("X-RateLimit-Remaining", "9"))
                .andExpect(header().string("Retry-After", "0"));
    }

    @Test
    void exceedingLimit_returns429_withRetryAfter() throws Exception {
        String apiKey = "sk-gateway-burst";

        // FREE 등급은 100ms마다 토큰 1개가 충전되는데 MockMvc + Redis 왕복이 요청당 약 10ms라,
        // 요청 10번으로 버킷을 비우면 충전 경계에 걸려 결과가 흔들린다. 빈 버킷 상태를 직접 심어 결정적으로 검증한다.
        // (10회 허용 후 거절되는 버스트 동작은 LuaRedisRateLimiterTest에서 검증한다.)
        seedEmptyBucket(apiKey);

        mockMvc.perform(post("/v1/chat")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(header().string("Retry-After", "1"));
    }

    private void seedEmptyBucket(String apiKey) {
        Long redisNowMillis = redisTemplate.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        redisTemplate.opsForHash().putAll(RedisTokenBucketKeys.apiKeyBucket(apiKey), Map.of(
                "tokens", "0",
                "lastRefillTime", Long.toString(redisNowMillis)));
    }
}
