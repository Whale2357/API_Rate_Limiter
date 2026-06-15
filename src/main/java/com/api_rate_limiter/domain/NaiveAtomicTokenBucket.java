package com.api_rate_limiter.domain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * V3-1: AtomicLong 변수만 사용하고 Check-Then-Act 로직은 그대로 유지한 구현.
 * 개별 연산은 원자적이지만 전체 consume 흐름은 원자적이지 않아 경합 시 정합성이 깨진다.
 */
public class NaiveAtomicTokenBucket implements TokenBucket {

    private final AtomicLong tokens;
    private long lastRefillTime;
    private final int capacity;
    private final double refillRate;

    public NaiveAtomicTokenBucket() {
        this(AbstractTokenBucket.DEFAULT_CAPACITY, AbstractTokenBucket.DEFAULT_REFILL_RATE);
    }

    public NaiveAtomicTokenBucket(int capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = new AtomicLong(capacity);
        this.lastRefillTime = System.nanoTime();
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * refillRate;

        if (tokensToAdd > 0) {
            long current = tokens.get();
            long newTokens = (long) Math.min(capacity, current + tokensToAdd);
            tokens.set(newTokens);
            lastRefillTime = now;
        }
    }

    @Override
    public boolean tryConsume() {
        refill();

        if (tokens.get() >= 1) {
            tokens.decrementAndGet();
            return true;
        }
        return false;
    }

    @Override
    public double getTokens() {
        return tokens.get();
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
