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

    /**
     * Creates a configuration with sensible defaults.
     */
    public static ValkeyLockConfig withDefaults() {
        return new ValkeyLockConfig("redis://localhost:6379", "maestro:lock:", Duration.ofSeconds(30));
    }
}
