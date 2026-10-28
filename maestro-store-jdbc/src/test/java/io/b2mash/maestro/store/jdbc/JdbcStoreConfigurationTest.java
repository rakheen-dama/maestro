package io.b2mash.maestro.store.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link JdbcStoreConfiguration}: the compact-constructor
 * null-checks and the {@link JdbcStoreConfiguration#withDefaults} factory —
 * the only logic in this record that doesn't require a live database.
 */
@DisplayName("JdbcStoreConfiguration")
class JdbcStoreConfigurationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("rejects a null ObjectMapper")
    void constructor_rejectsNullObjectMapper() {
        var ex = assertThrows(NullPointerException.class,
                () -> new JdbcStoreConfiguration(null));
        assertEquals("objectMapper", ex.getMessage());
    }

    @Test
    @DisplayName("accepts a valid mapper and exposes it unchanged")
    void constructor_acceptsValidArguments() {
        var config = new JdbcStoreConfiguration(mapper);

        assertSame(mapper, config.objectMapper());
    }

    @Test
    @DisplayName("the table prefix is the fixed 'maestro_' constant")
    void tablePrefix_isMaestro() {
        var config = JdbcStoreConfiguration.withDefaults(mapper);

        assertEquals("maestro_", JdbcStoreConfiguration.TABLE_PREFIX);
        assertSame(mapper, config.objectMapper());
    }
}
