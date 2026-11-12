package io.b2mash.maestro.store.postgres.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the migrations shipped at {@code main} @ {@code cbf5a2a}: every one is
 * still shipped by this module, byte for byte up to line endings. Flyway
 * checksums a migration's content, so a database that recorded a shipped
 * migration fails validation ("checksum mismatch") under any build that edits
 * it, and reads it as "missing" under any build that renames or deletes it.
 * Never change a shipped migration; add the next version in 101–199.
 *
 * <p>The reference is the frozen copy under {@code upgrade/cbf5a2a/}, written
 * once from git by {@code scripts/capture-upgrade-fixture.sh}; see
 * {@link FrozenMigrations}. No database is needed.
 */
@DisplayName("Migrations shipped at cbf5a2a are frozen")
class ShippedMigrationsFrozenTest {

    static final String CBF5A2A = "cbf5a2a06acfba9b0f7ae23f37b3f1f1713928f7";

    @Test
    @DisplayName("the frozen set is exactly what cbf5a2a shipped, and each copy matches its recorded hash")
    void theFrozenSetIsEverythingShippedAtCbf5a2a() {
        var frozen = FrozenMigrations.named("cbf5a2a");

        assertThat(frozen.ref()).isEqualTo(CBF5A2A);
        assertThat(frozen.files()).containsExactly(
                "store/V1__create_maestro_schema.sql", "store/V2__create_indexes.sql",
                "store/V3__timer_lookup_index.sql", "store/V4__signal_trace_context.sql",
                "store/V5__signal_received_at_default.sql", "store/V6__instance_lease_epoch.sql",
                "store/V7__workflow_event_args_hash.sql", "store/V8__retention_indexes.sql",
                "store/V9__signal_message_id.sql", "lock/V100__maestro_lock_postgres.sql",
                "store/V101__recovery_scan_index.sql");
        for (var file : frozen.files()) {
            assertThat(FrozenMigrations.sha256(FrozenMigrations.lf(frozen.read(file))))
                    .as("frozen copy of %s hashes to its SOURCE entry", file)
                    .isEqualTo(frozen.recordedSha256(file));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frozenFiles")
    @DisplayName("every migration shipped at cbf5a2a is still shipped, unchanged")
    void everyMigrationShippedAtCbf5a2aIsStillShippedUnchanged(String file) throws IOException {
        var frozen = FrozenMigrations.named("cbf5a2a");
        byte[] shipped;
        try (var in = getClass().getClassLoader().getResourceAsStream("db/maestro/" + file)) {
            assertThat(in)
                    .as(file + " shipped at cbf5a2a is no longer shipped (deleted or renamed)")
                    .isNotNull();
            shipped = in.readAllBytes();
        }

        assertThat(FrozenMigrations.sha256(FrozenMigrations.lf(shipped)))
                .as(file + " was edited after it shipped at cbf5a2a; never edit a shipped migration,"
                        + " add the next version in 101-199")
                .isEqualTo(FrozenMigrations.sha256(FrozenMigrations.lf(frozen.read(file))));
    }

    static List<String> frozenFiles() {
        return FrozenMigrations.named("cbf5a2a").files();
    }

    @Test
    @DisplayName("line endings do not count, but content does")
    void lineEndingsDoNotCountButContentDoes() {
        assertThat(FrozenMigrations.lf("a;\r\nb;\n".getBytes(UTF_8))).isEqualTo("a;\nb;\n".getBytes(UTF_8));
        assertThat(FrozenMigrations.sha256(FrozenMigrations.lf("a;\r\nb;\n".getBytes(UTF_8))))
                .isNotEqualTo(FrozenMigrations.sha256("a;\nc;\n".getBytes(UTF_8)));
    }
}
