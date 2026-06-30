package com.api_rate_limiter.interceptor;

import com.api_rate_limiter.filter.ApiKeyFilter;
import com.api_rate_limiter.ratelimiter.LuaRedisRateLimiter;
import com.api_rate_limiter.ratelimiter.RateLimitResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * V5: 컨트롤러 진입 전 전처리 단계에서 Redis Lua 엔진으로 토큰을 차감한다.
 * 허용 시 표준 헤더를 주입하고, 한도 초과 시 429로 즉시 차단(Early Return)한다.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";

    private final LuaRedisRateLimiter rateLimiter;

    public RateLimitInterceptor(LuaRedisRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Object apiKeyAttribute = request.getAttribute(ApiKeyFilter.API_KEY_ATTRIBUTE);
        if (apiKeyAttribute == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
            return false;
        }

        String apiKey = apiKeyAttribute.toString();
        RateLimitResult result = rateLimiter.tryAcquire(apiKey);

        response.setHeader(HEADER_LIMIT, Integer.toString(result.limit()));
        response.setHeader(HEADER_REMAINING, Long.toString(Math.max(0, result.remaining())));
        response.setHeader(HttpHeaders.RETRY_AFTER, Integer.toString(result.retryAfterSeconds()));

        if (result.allowed()) {
            return true;
        }

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"Too Many Requests\"}");
        return false;
    }
}
