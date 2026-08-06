package io.b2mash.maestro.lock.valkey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the audit F5 fix: {@link ValkeyLockAutoConfiguration#resolveRedisUri}
 * must read {@code spring.data.redis.host}/{@code port}/{@code password}/
 * {@code username}/{@code ssl.enabled}/{@code database} — the properties
 * {@code docs/configuration.md}'s own Complete Example configures — not just
 * {@code spring.data.redis.url} and {@code maestro.lock.valkey.uri}. Before
 * this fix, a deployment that only set host/port silently connected to
 * {@code redis://localhost:6379}.
 */
@DisplayName("ValkeyLockAutoConfiguration.resolveRedisUri (audit F5)")
class ValkeyLockAutoConfigurationUriTest {

    @Test
    @DisplayName("host + port build a URI — RED today: falls through to redis://localhost:6379")
    void hostAndPortBuildUri() {
        var env = new MockEnvironment()
                .withProperty("spring.data.redis.host", "lock-host")
                .withProperty("spring.data.redis.port", "7000");

        assertThat(ValkeyLockAutoConfiguration.resolveRedisUri(env))
                .isEqualTo("redis://lock-host:7000");
    }

    @Test
    @DisplayName("spring.data.redis.url wins over host/port")
    void urlWinsOverHost() {
        var env = new MockEnvironment()
                .withProperty("spring.data.redis.url", "redis://url-host:1111")
                .withProperty("spring.data.redis.host", "lock-host")
                .withProperty("spring.data.redis.port", "7000");

        assertThat(ValkeyLockAutoConfiguration.resolveRedisUri(env))
                .isEqualTo("redis://url-host:1111");
    }

    @Test
    @DisplayName("maestro.lock.valkey.uri wins over host/port")
    void maestroUriWinsOverHost() {
        var env = new MockEnvironment()
                .withProperty("maestro.lock.valkey.uri", "redis://custom-host:2222")
                .withProperty("spring.data.redis.host", "lock-host")
                .withProperty("spring.data.redis.port", "7000");

        assertThat(ValkeyLockAutoConfiguration.resolveRedisUri(env))
                .isEqualTo("redis://custom-host:2222");
    }

    @Test
    @DisplayName("password (no username) is embedded in the URI")
    void passwordWithoutUsername() {
        var env = new MockEnvironment()
                .withProperty("spring.data.redis.host", "lock-host")
                .withProperty("spring.data.redis.port", "7000")
                .withProperty("spring.data.redis.password", "secret");

        assertThat(ValkeyLockAutoConfiguration.resolveRedisUri(env))
                .isEqualTo("redis://secret@lock-host:7000");
    }

    @Test
    @DisplayName("password + username are both embedded in the URI")
    void passwordWithUsername() {
        var env = new MockEnvironment()
                .withProperty("spring.data.redis.host", "lock-host")
                .withProperty("spring.data.redis.port", "7000")
                .withProperty("spring.data.redis.username", "app-user")
                .withProperty("spring.data.redis.password", "secret");

        assertThat(ValkeyLockAutoConfiguration.resolveRedisUri(env))
                .isEqualTo("redis://app-user:secret@lock-host:7000");
    }

    @Test
    @DisplayName("ssl.enabled=true switches the scheme to rediss")
    void sslEnabled() {
        var env = new MockEnvironment()
                .withProperty("spring.data.redis.host", "lock-host")
                .withProperty("spring.data.redis.port", "7000")
                .withProperty("spring.data.redis.ssl.enabled", "true");

        assertThat(ValkeyLockAutoConfiguration.resolveRedisUri(env))
                .isEqualTo("rediss://lock-host:7000");
    }

    @Test
    @DisplayName("database index is appended to the URI path")
    void databaseIndex() {
        var env = new MockEnvironment()
                .withProperty("spring.data.redis.host", "lock-host")
                .withProperty("spring.data.redis.port", "7000")
                .withProperty("spring.data.redis.database", "3");

        assertThat(ValkeyLockAutoConfiguration.resolveRedisUri(env))
                .isEqualTo("redis://lock-host:7000/3");
    }

    @Test
    @DisplayName("no properties set at all falls back to the documented default")
    void noPropertiesFallsBackToDefault() {
        var env = new MockEnvironment();

        assertThat(ValkeyLockAutoConfiguration.resolveRedisUri(env))
                .isEqualTo("redis://localhost:6379");
    }
}
