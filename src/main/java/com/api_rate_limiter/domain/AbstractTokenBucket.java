package com.api_rate_limiter.domain;

public abstract class AbstractTokenBucket implements TokenBucket {

    public static final int DEFAULT_CAPACITY = 10;
    public static final double DEFAULT_REFILL_RATE = 10.0;

    protected double tokens;
    protected long lastRefillTime;
    protected final int capacity;
    protected final double refillRate;

    protected AbstractTokenBucket() {
        this(DEFAULT_CAPACITY, DEFAULT_REFILL_RATE);
    }

    protected AbstractTokenBucket(int capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTime = System.nanoTime();
    }

    protected void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * refillRate;

        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }

    @Override
    public double getTokens() {
        return tokens;
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
