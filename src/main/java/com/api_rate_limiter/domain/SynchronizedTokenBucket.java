package com.api_rate_limiter.domain;

public class SynchronizedTokenBucket extends AbstractTokenBucket {

    public SynchronizedTokenBucket(int capacity, double refillRate) {
        super(capacity, refillRate);
    }

    @Override
    public boolean tryConsume() {
        synchronized (this) {
            return doConsume();
        }
    }
}
