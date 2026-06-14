package com.api_rate_limiter.config;

import com.api_rate_limiter.domain.AbstractTokenBucket;
import com.api_rate_limiter.domain.ReentrantLockTokenBucket;
import com.api_rate_limiter.domain.SynchronizedTokenBucket;
import com.api_rate_limiter.domain.TokenBucket;
import com.api_rate_limiter.domain.V1TokenBucket;

public enum RateLimiterStrategy {

    NO_LOCK {
        @Override
        public TokenBucket create(int capacity, double refillRate) {
            return new V1TokenBucket(capacity, refillRate);
        }
    },
    SYNCHRONIZED {
        @Override
        public TokenBucket create(int capacity, double refillRate) {
            return new SynchronizedTokenBucket(capacity, refillRate);
        }
    },
    REENTRANT_LOCK {
        @Override
        public TokenBucket create(int capacity, double refillRate) {
            return new ReentrantLockTokenBucket(capacity, refillRate);
        }
    };

    public TokenBucket create() {
        return create(AbstractTokenBucket.DEFAULT_CAPACITY, AbstractTokenBucket.DEFAULT_REFILL_RATE);
    }

    public abstract TokenBucket create(int capacity, double refillRate);
}
