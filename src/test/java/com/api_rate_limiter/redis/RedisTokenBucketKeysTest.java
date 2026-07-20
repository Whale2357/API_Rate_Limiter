package com.api_rate_limiter.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RedisTokenBucketKeysTest {

    @Test
    void apiKeyBucket_doesNotContainPlainApiKey() {
        String apiKey = "sk-pro-sensitive-key";

        String redisKey = RedisTokenBucketKeys.apiKeyBucket(apiKey);

        assertFalse(redisKey.contains(apiKey));
        assertEquals("bucket:" + sha256Hex(apiKey), redisKey);
    }

    @Test
    void apiKeyBucket_isDeterministicForSameKey() {
        String apiKey = "sk-userA";

        assertEquals(
                RedisTokenBucketKeys.apiKeyBucket(apiKey),
                RedisTokenBucketKeys.apiKeyBucket(apiKey));
    }

    @Test
    void apiKeyBucket_differsForDifferentKeys() {
        assertNotEquals(
                RedisTokenBucketKeys.apiKeyBucket("sk-userA"),
                RedisTokenBucketKeys.apiKeyBucket("sk-userB"));
    }

    private static String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
