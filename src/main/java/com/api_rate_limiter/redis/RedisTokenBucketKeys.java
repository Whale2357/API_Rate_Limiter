package com.api_rate_limiter.redis;

public final class RedisTokenBucketKeys {

    private static final String USER_BUCKET_PREFIX = "bucket:user:";
    private static final String API_KEY_BUCKET_PREFIX = "bucket:";

    private RedisTokenBucketKeys() {
    }

    public static String userBucket(String userId) {
        return USER_BUCKET_PREFIX + userId;
    }

    public static String apiKeyBucket(String apiKey) {
        return API_KEY_BUCKET_PREFIX + apiKey;
    }
}
