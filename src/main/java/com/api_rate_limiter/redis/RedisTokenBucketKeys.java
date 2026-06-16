package com.api_rate_limiter.redis;

public final class RedisTokenBucketKeys {

    private static final String USER_BUCKET_PREFIX = "bucket:user:";

    private RedisTokenBucketKeys() {
    }

    public static String userBucket(String userId) {
        return USER_BUCKET_PREFIX + userId;
    }
}
