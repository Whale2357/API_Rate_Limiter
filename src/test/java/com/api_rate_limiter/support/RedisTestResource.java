package com.api_rate_limiter.support;

import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public final class RedisTestResource {

    private static GenericContainer<?> container;

    private RedisTestResource() {
    }

    public static StringRedisTemplate connect() {
        StringRedisTemplate local = tryConnect("localhost", 6379);
        if (local != null) {
            return local;
        }

        if (DockerClientFactory.instance().isDockerAvailable()) {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);
            container.start();
            StringRedisTemplate remote = tryConnect(container.getHost(), container.getMappedPort(6379));
            if (remote != null) {
                return remote;
            }
        }

        Assumptions.abort("Redis not available — run: docker compose up -d");
        return null;
    }

    private static StringRedisTemplate tryConnect(String host, int port) {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.setValidateConnection(true);
        try {
            connectionFactory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
            template.afterPropertiesSet();
            RedisConnectionFactory factory = template.getConnectionFactory();
            if (factory == null) {
                connectionFactory.destroy();
                return null;
            }
            factory.getConnection().ping();
            return template;
        } catch (Exception e) {
            connectionFactory.destroy();
            return null;
        }
    }
}
