package com.api_rate_limiter.domain;

public abstract class AbstractTokenBucket implements TokenBucket {

    public static final int DEFAULT_CAPACITY = 10;
    public static final double DEFAULT_REFILL_RATE = 10.0;

    private final int capacity;
    private final double refillRate;
    private double tokens;
    private long lastRefillTime;

    protected AbstractTokenBucket(int capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTime = System.nanoTime();
    }

    /**
     * 동기화 없이 refill → consume을 수행한다. 동시성 보장은 호출하는 하위 클래스의 책임이다.
     */
    protected final boolean doConsume() {
        refill();

        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * refillRate;

        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }
}
