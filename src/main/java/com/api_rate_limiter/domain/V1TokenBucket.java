package com.api_rate_limiter.domain;

public class V1TokenBucket extends AbstractTokenBucket {

    public V1TokenBucket(int capacity, double refillRate) {
        super(capacity, refillRate);
    }

    @Override
    public boolean tryConsume() {
        return doConsume();
    }
}
