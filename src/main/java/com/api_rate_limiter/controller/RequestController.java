package com.api_rate_limiter.controller;

import com.api_rate_limiter.dto.RateLimitResponse;
import com.api_rate_limiter.service.RateLimiterService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
