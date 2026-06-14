package com.api_rate_limiter;

import com.api_rate_limiter.config.RateLimiterStrategy;
import com.api_rate_limiter.factory.TokenBucketFactory;
import com.api_rate_limiter.manager.TokenBucketManager;
import com.api_rate_limiter.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterServiceTest {

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        TokenBucketFactory factory = new TokenBucketFactory(RateLimiterStrategy.NO_LOCK);
        rateLimiterService = new RateLimiterService(new TokenBucketManager(factory));
    }

    private boolean request(String userId, int requestNumber) {
        boolean allowed = rateLimiterService.tryAcquire(userId);
        System.out.printf("userId=%s, request #%d -> {\"allowed\": %s}%n", userId, requestNumber, allowed);
        return allowed;
    }

    @Test
    void scenario1_sameUserTenRequests_allAllowed() {
        String userId = "user-1";

        for (int i = 0; i < 10; i++) {
            assertTrue(request(userId, i + 1), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void scenario2_eleventhRequest_rejected() {
        String userId = "user-2";

        for (int i = 0; i < 10; i++) {
            assertTrue(request(userId, i + 1));
        }

        assertFalse(request(userId, 11), "11th request should be rejected");
    }

    @Test
    void scenario3_waitOneSecond_requestsAvailableAgain() throws InterruptedException {
        String userId = "user-3";

        for (int i = 0; i < 10; i++) {
            assertTrue(request(userId, i + 1));
        }

        assertFalse(request(userId, 11));

        System.out.println("waiting 1 second for token refill...");
        Thread.sleep(1000);

        assertTrue(request(userId, 12), "Request should be allowed after 1 second refill");
    }

    @Test
    void differentUsers_haveIndependentBuckets() {
        for (int i = 0; i < 10; i++) {
            assertTrue(request("user-a", i + 1));
            assertTrue(request("user-b", i + 1));
        }

        assertFalse(request("user-a", 11));
        assertFalse(request("user-b", 11));
    }
}
