package com.api_rate_limiter.domain;

public class SynchronizedTokenBucket extends AbstractTokenBucket {

    public SynchronizedTokenBucket() {
        super();
    }

    public SynchronizedTokenBucket(int capacity, double refillRate) {
        super(capacity, refillRate);
    }

    @Override
    public boolean tryConsume() {
        synchronized (this) {
            refill();

            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
