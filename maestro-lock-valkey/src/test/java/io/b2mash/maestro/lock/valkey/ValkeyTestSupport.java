package io.b2mash.maestro.lock.valkey;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for Valkey integration tests.
 *
 * <p>Provides a shared Testcontainers Valkey instance, Lettuce client
 * setup, and per-test cleanup via {@code FLUSHALL}.
 */
@Testcontainers
abstract class ValkeyTestSupport {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> valkey =
            new GenericContainer<>("valkey/valkey:8-alpine")
                    .withExposedPorts(6379);

    protected RedisClient redisClient;
    protected StatefulRedisConnection<String, String> connection;
    protected RedisCommands<String, String> commands;

    @BeforeEach
    void setUpValkey() {
        var uri = RedisURI.builder()
                .withHost(valkey.getHost())
                .withPort(valkey.getMappedPort(6379))
                .build();
        redisClient = RedisClient.create(uri);
        connection = redisClient.connect();
        commands = connection.sync();
        commands.flushall();
    }

    @AfterEach
    void tearDownValkey() {
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }
}
