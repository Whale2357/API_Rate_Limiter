package com.api_rate_limiter.domain;

import java.util.concurrent.locks.ReentrantLock;

public class ReentrantLockTokenBucket extends AbstractTokenBucket {

    private final ReentrantLock lock;

    public ReentrantLockTokenBucket() {
        this(DEFAULT_CAPACITY, DEFAULT_REFILL_RATE, false);
    }

    public ReentrantLockTokenBucket(int capacity, double refillRate) {
        this(capacity, refillRate, false);
    }

    public ReentrantLockTokenBucket(int capacity, double refillRate, boolean fair) {
        super(capacity, refillRate);
        this.lock = new ReentrantLock(fair);
    }

    @Override
    public boolean tryConsume() {
        lock.lock();
        try {
            refill();

            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
}
