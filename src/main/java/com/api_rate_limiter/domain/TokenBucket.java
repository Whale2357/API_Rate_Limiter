package com.api_rate_limiter.domain;

public interface TokenBucket {

    boolean tryConsume();
}
