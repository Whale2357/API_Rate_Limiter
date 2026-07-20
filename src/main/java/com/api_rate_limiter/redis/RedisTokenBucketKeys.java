package com.api_rate_limiter.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RedisTokenBucketKeys {

    private static final String USER_BUCKET_PREFIX = "bucket:user:";
    private static final String API_KEY_BUCKET_PREFIX = "bucket:";

    private RedisTokenBucketKeys() {
    }

    public static String userBucket(String userId) {
        return USER_BUCKET_PREFIX + userId;
    }

    /**
     * API Key 원문을 Redis에 저장하지 않도록 SHA-256 해시 후 키를 생성한다.
     */
    public static String apiKeyBucket(String apiKey) {
        return API_KEY_BUCKET_PREFIX + sha256Hex(apiKey);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
