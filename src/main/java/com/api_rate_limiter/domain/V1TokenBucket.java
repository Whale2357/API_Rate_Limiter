package com.api_rate_limiter.domain;

public class V1TokenBucket extends AbstractTokenBucket {

    public V1TokenBucket() {
        super();
    }

    public V1TokenBucket(int capacity, double refillRate) {
        super(capacity, refillRate);
    }

    @Override
    public boolean tryConsume() {
        refill();

        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
