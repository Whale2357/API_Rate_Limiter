package com.api_rate_limiter;

import com.api_rate_limiter.controller.ChatController;
import com.api_rate_limiter.filter.ApiKeyFilter;
import com.api_rate_limiter.interceptor.RateLimitInterceptor;
import com.api_rate_limiter.ratelimiter.LuaRedisRateLimiter;
import com.api_rate_limiter.ratelimiter.RateLimitLuaScript;
import com.api_rate_limiter.service.AiService;
import com.api_rate_limiter.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(new AiService()))
                .addFilters(new ApiKeyFilter())
                .addMappedInterceptors(new String[]{"/v1/**"}, new RateLimitInterceptor(rateLimiter))
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
        String apiKey = "Bearer sk-gateway-burst";

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/v1/chat")
                            .header("Authorization", apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"hi\"}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/v1/chat")
                        .header("Authorization", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(header().string("Retry-After", "1"));
    }
}
