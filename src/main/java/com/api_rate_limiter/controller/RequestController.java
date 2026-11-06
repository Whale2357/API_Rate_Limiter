package com.api_rate_limiter.controller;

import com.api_rate_limiter.dto.RateLimitResponse;
import com.api_rate_limiter.service.RateLimiterService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * V1~V4 동시성 전략 비교용 실험 엔드포인트. 운영 경로는 /v1/** 게이트웨이(LuaRedisRateLimiter)이며,
 * 이 컨트롤러는 experiment 프로필에서만 등록된다.
 */
@Profile("experiment")
@RestController
@RequestMapping("/api")
public class RequestController {

    private final RateLimiterService rateLimiterService;

    public RequestController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @PostMapping("/request")
    public RateLimitResponse handleRequest(@RequestParam String userId) {
        boolean allowed = rateLimiterService.tryAcquire(userId);
        return new RateLimitResponse(allowed);
    }
}
