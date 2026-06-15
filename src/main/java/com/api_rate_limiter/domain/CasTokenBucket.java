package com.api_rate_limiter.domain;

import java.util.concurrent.atomic.AtomicReference;

/**
 * V3-2/V3-3: CAS 루프로 consume을 락프리 구현하고,
 * 불변 BucketState + AtomicReference로 refill까지 단일 CAS 전환으로 처리한다.
 */
public class CasTokenBucket implements TokenBucket {

    private record BucketState(double tokens, long lastRefillTime) {}

    private final AtomicReference<BucketState> state;
    private final int capacity;
    private final double refillRate;

    public CasTokenBucket() {
        this(AbstractTokenBucket.DEFAULT_CAPACITY, AbstractTokenBucket.DEFAULT_REFILL_RATE);
    }

    public CasTokenBucket(int capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.state = new AtomicReference<>(new BucketState(capacity, System.nanoTime()));
    }

    private BucketState refill(BucketState current) {
        long now = System.nanoTime();
        long elapsedNanos = now - current.lastRefillTime();
        double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * refillRate;

        if (tokensToAdd > 0) {
            return new BucketState(Math.min(capacity, current.tokens() + tokensToAdd), now);
        }
        return current;
    }

    @Override
    public boolean tryConsume() {
        while (true) {
            BucketState current = state.get();
            BucketState refilled = refill(current);

            if (refilled.tokens() < 1.0) {
                if (!refilled.equals(current) && !state.compareAndSet(current, refilled)) {
                    continue;
                }
                return false;
            }

            BucketState next = new BucketState(refilled.tokens() - 1.0, refilled.lastRefillTime());
            if (state.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    @Override
    public double getTokens() {
        return state.get().tokens();
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public double getRefillRate() {
        return refillRate;
    }
}
