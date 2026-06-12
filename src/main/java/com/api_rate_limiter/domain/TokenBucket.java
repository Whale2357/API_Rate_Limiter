package com.api_rate_limiter.domain;

public class TokenBucket {

    private static final int DEFAULT_CAPACITY = 10;
    private static final double DEFAULT_REFILL_RATE = 10.0;

    private double tokens;
    private long lastRefillTime;
    private final int capacity;
    private final double refillRate;

    public TokenBucket() {
        this(DEFAULT_CAPACITY, DEFAULT_REFILL_RATE);
    }

    public TokenBucket(int capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity;
        this.lastRefillTime = System.nanoTime();
    }

    public void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillTime;
        double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * refillRate;

        if (tokensToAdd > 0) {
            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }

    public boolean tryConsume() {
        refill();

        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    public double getTokens() {
        return tokens;
    }

    public int getCapacity() {
        return capacity;
    }

    public double getRefillRate() {
        return refillRate;
    }
}
