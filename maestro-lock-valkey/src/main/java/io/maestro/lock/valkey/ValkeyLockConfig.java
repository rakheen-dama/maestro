package io.maestro.lock.valkey;

import java.time.Duration;

/**
 * Resolved configuration for the Valkey distributed lock.
 *
 * <p>Created by {@link io.maestro.lock.valkey.config.ValkeyLockAutoConfiguration}
 * from {@code MaestroProperties.LockProperties} and environment resolution.
 *
 * @param redisUri   the Redis/Valkey URI (e.g., {@code "redis://localhost:6379"})
 * @param keyPrefix  prefix for all lock keys (e.g., {@code "maestro:lock:"})
 * @param defaultTtl default lock TTL when not specified by the caller
 */
public record ValkeyLockConfig(
        String redisUri,
        String keyPrefix,
        Duration defaultTtl
) {

    /** Default Redis/Valkey URI when no configuration is provided. */
    public static final String DEFAULT_REDIS_URI = "redis://localhost:6379";

    /**
     * Creates a configuration with sensible defaults.
     */
    public static ValkeyLockConfig withDefaults() {
        return new ValkeyLockConfig(DEFAULT_REDIS_URI, "maestro:lock:", Duration.ofSeconds(30));
    }
}
