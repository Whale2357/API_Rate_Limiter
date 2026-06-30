package com.api_rate_limiter.ratelimiter;

/**
 * V5: 단일 tryAcquire 결과. 표준 레이트 리밋 헤더를 구성하는 데 필요한 값을 함께 담는다.
 */
public record RateLimitResult(boolean allowed, int limit, long remaining, int retryAfterSeconds) {
}
