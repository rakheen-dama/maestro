package io.maestro.lock.valkey.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.SignalNotifier;
import io.maestro.lock.valkey.ValkeyDistributedLock;
import io.maestro.lock.valkey.ValkeySignalNotifier;
import io.maestro.spring.config.MaestroAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-configuration for Valkey-based distributed locking and signal notification.
 *
 * <p>Activates when:
 * <ul>
 *   <li>Lettuce's {@link RedisClient} is on the classpath</li>
 *   <li>{@code maestro.lock.type} is {@code "valkey"} (default)</li>
 * </ul>
 *
 * <p>Creates the following beans:
 * <ul>
 *   <li>{@link RedisClient} — shared client for all Valkey connections</li>
 *   <li>{@link ValkeyDistributedLock} — the {@link DistributedLock} SPI implementation</li>
 *   <li>{@link ValkeySignalNotifier} — the {@link SignalNotifier} SPI implementation</li>
 * </ul>
 *
 * <p>Redis URI is resolved from:
 * <ol>
 *   <li>{@code spring.data.redis.url} (standard Spring property)</li>
 *   <li>{@code maestro.lock.valkey.uri} (Maestro-specific)</li>
 *   <li>{@code redis://localhost:6379} (default)</li>
 * </ol>
 *
 * <p>All beans are guarded with {@link ConditionalOnMissingBean} to allow
 * user overrides.
 *
 * @see ValkeyDistributedLock
 * @see ValkeySignalNotifier
 */
@AutoConfiguration(after = MaestroAutoConfiguration.class)
@ConditionalOnClass(RedisClient.class)
@ConditionalOnProperty(prefix = "maestro.lock", name = "type", havingValue = "valkey", matchIfMissing = true)
public class ValkeyLockAutoConfiguration {

    private static final String DEFAULT_REDIS_URI = "redis://localhost:6379";

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "maestroRedisClient")
    public RedisClient maestroRedisClient(Environment env) {
        var uri = resolveRedisUri(env);
        return RedisClient.create(RedisURI.create(uri));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "maestroLockConnection")
    public StatefulRedisConnection<String, String> maestroLockConnection(RedisClient maestroRedisClient) {
        return maestroRedisClient.connect();
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    public ValkeyDistributedLock valkeyDistributedLock(
            StatefulRedisConnection<String, String> maestroLockConnection
    ) {
        return new ValkeyDistributedLock(maestroLockConnection.sync());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SignalNotifier.class)
    public ValkeySignalNotifier valkeySignalNotifier(RedisClient maestroRedisClient) {
        var pubSubConnection = maestroRedisClient.connectPubSub();
        var publishConnection = maestroRedisClient.connect();
        return new ValkeySignalNotifier(pubSubConnection, publishConnection);
    }

    private static String resolveRedisUri(Environment env) {
        // 1. Standard Spring Data Redis property
        var standard = env.getProperty("spring.data.redis.url");
        if (standard != null && !standard.isBlank()) {
            return standard;
        }
        // 2. Maestro-specific property
        var custom = env.getProperty("maestro.lock.valkey.uri");
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        // 3. Default
        return DEFAULT_REDIS_URI;
    }
}
