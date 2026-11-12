package io.b2mash.maestro.store.postgres.schema;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A frozen Maestro migration set: every migration a past commit shipped, as
 * {@code scripts/capture-upgrade-fixture.sh migrations <ref> <name>} wrote it
 * from git into {@code src/test/resources/upgrade/<name>/<component>/<file>},
 * with a {@code SOURCE} manifest recording the ref and each file's SHA-256.
 *
 * <p>It pins what an older build shipped, so tests can (a) prove this build
 * still ships each of those migrations unchanged and (b) migrate a database
 * exactly as that older build did. A frozen set is evidence about the past:
 * it is never edited and never regenerated (see the script's header).
 *
 * <p>Line endings: Flyway's checksum ignores them, so comparisons go through
 * {@link #lf(byte[])}, which folds CRLF to LF and changes nothing else — a
 * Windows checkout does not fail the guard, a real edit still does.
 *
 * <p>Thread safety: instances are immutable after {@link #named(String)}.
 */
final class FrozenMigrations {

    /** Component directories a frozen set may hold, in the order Flyway is given them. */
    private static final List<String> COMPONENTS = List.of("store", "lock", "messaging");

    private static final ClassLoader CL = FrozenMigrations.class.getClassLoader();

    private final String name;
    private final String ref;
    /** {@code <component>/<file>} → recorded SHA-256, ordered by version number. */
    private final Map<String, String> recorded;

    private FrozenMigrations(String name, String ref, Map<String, String> recorded) {
        this.name = name;
        this.ref = ref;
        this.recorded = recorded;
    }

    /**
     * Loads {@code classpath:upgrade/<name>/SOURCE}.
     *
     * @throws IllegalArgumentException if no such frozen set is on the classpath
     */
    static FrozenMigrations named(String name) {
        var resource = "upgrade/" + name + "/SOURCE";
        var source = new Properties();
        try (var in = CL.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No frozen migration set: classpath:" + resource);
            }
            source.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read classpath:" + resource, e);
        }
        var ref = Objects.requireNonNull(source.getProperty("ref"), resource + " has no ref");
        var recorded = new TreeMap<String, String>(Comparator
                .comparingInt(FrozenMigrations::version)
                .thenComparing(Comparator.naturalOrder()));
        for (var key : source.stringPropertyNames()) {
            if (key.equals("ref") || key.equals("captured")) {
                continue;
            }
            recorded.put(key, source.getProperty(key));
        }
        return new FrozenMigrations(name, ref, Collections.unmodifiableMap(recorded));
    }

    /** The full 40-character SHA of the commit the set was captured at. */
    String ref() {
        return ref;
    }

    /** Every frozen migration as {@code <component>/<file>}, ordered by version number. */
    List<String> files() {
        return List.copyOf(recorded.keySet());
    }

    /** The SHA-256 that {@code SOURCE} records for {@code file} (its git blob bytes). */
    String recordedSha256(String file) {
        return Objects.requireNonNull(recorded.get(file), () -> file + " is not in upgrade/" + name + "/SOURCE");
    }

    /** The frozen copy's bytes, as they are on disk. */
    byte[] read(String file) {
        var resource = "upgrade/" + name + "/" + file;
        try (var in = CL.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No frozen migration: classpath:" + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read classpath:" + resource, e);
        }
    }

    /** {@code filesystem:<abs path>/<component>} for each component directory this set holds. */
    String[] flywayLocations() {
        return COMPONENTS.stream()
                .map(component -> CL.getResource("upgrade/" + name + "/" + component))
                .filter(Objects::nonNull)
                .map(url -> {
                    try {
                        return "filesystem:" + Path.of(url.toURI());
                    } catch (URISyntaxException e) {
                        throw new IllegalStateException("Unusable resource URL " + url, e);
                    }
                })
                .toArray(String[]::new);
    }

    /**
     * A Flyway configured as the older build's {@link MaestroSchemaInitializer}
     * in {@code flyway} mode, over exactly this frozen set: Maestro's own
     * history table, baseline at {@code 0} on a non-empty schema.
     *
     * @param outOfOrder whether that build's initializer set {@code outOfOrder(true)}
     */
    Flyway olderBuild(DataSource ds, boolean outOfOrder) {
        return Flyway.configure(CL)
                .dataSource(ds)
                .table(MaestroSchemaInitializer.HISTORY_TABLE)
                .locations(flywayLocations())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .outOfOrder(outOfOrder)
                .load();
    }

    /** Replaces each CRLF pair with LF; a lone CR, and everything else, is left alone. */
    static byte[] lf(byte[] raw) {
        var out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\r' && i + 1 < raw.length && raw[i + 1] == '\n') {
                continue;
            }
            out.write(raw[i]);
        }
        return out.toByteArray();
    }

    /** Lowercase hex SHA-256 of {@code bytes}. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** The integer after {@code V} in {@code <component>/V<n>__…}. */
    private static int version(String file) {
        var base = file.substring(file.lastIndexOf('/') + 1);
        return Integer.parseInt(base.substring(1, base.indexOf("__")));
    }
}
