package io.b2mash.maestro.store.postgres.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Creates and upgrades Maestro's own {@code maestro_*} tables with an
 * embedded Flyway run that Maestro owns end to end.
 *
 * <h2>Why Maestro has its own history table</h2>
 * <p>Maestro's migrations used to live in {@code classpath:db/migration} and
 * were applied by the <em>host application's</em> Flyway, into the host's
 * {@code flyway_schema_history}. That is Spring Boot's default location, so
 * Maestro's {@code V1} and a host's own {@code V1__init.sql} shared one
 * version namespace and collided, and a host that did not use Flyway at all
 * had to add the Flyway starter just to get Maestro's tables. Instead, every
 * Maestro Postgres module now ships its SQL under a module-specific location
 * and advertises that location in a {@value #LOCATIONS_RESOURCE} resource;
 * this class discovers all of them and applies them through a Flyway instance
 * configured with its own history table, {@value #HISTORY_TABLE}. The host's
 * Flyway, its {@code spring.flyway.*} settings and its
 * {@code flyway_schema_history} are never read or written.
 *
 * <p>Two locations share that one history, both shipped by
 * {@code maestro-store-postgres}: the store ({@code classpath:db/maestro/store},
 * V1–V9 and V101 onwards) and the Postgres lock
 * ({@code classpath:db/maestro/lock}, V100). The directory says which
 * component a migration belongs to; the version number does not. Every new
 * Maestro migration, store or lock, takes the next version above the highest
 * shipped, within 101–199; versions 1–9 and 100 are history. The reason is
 * older builds: every build since the lock band was folded into this module
 * resolves V100, and to such a build an applied migration it does not ship is
 * "future" — ignored by Flyway's default {@code ignoreMigrationPatterns=*:future}
 * — only if it sorts above V100. Below V100 it is "missing" and fails the older
 * node's startup. Band 200–299 belonged to the removed Postgres messaging
 * transport and is retired — never reuse it: databases that applied V200–V202
 * keep those history rows (Flyway ignores them as future versions in
 * validation, but they still count as the highest applied version, so every
 * later migration applies there only out of order; see below) and an orphan
 * {@code maestro_signal_queue}.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li>{@link SchemaMode#FLYWAY} — apply every pending migration.</li>
 *   <li>{@link SchemaMode#VALIDATE} — apply nothing; fail with
 *       {@link IllegalStateException} naming the pending versions if any are
 *       pending, and with Flyway's own validation error if an applied
 *       migration no longer matches the shipped SQL.</li>
 *   <li>{@link SchemaMode#NONE} — do nothing.</li>
 * </ul>
 *
 * <h2>Out-of-order application is load-bearing</h2>
 * <p>Flyway refuses a resolved migration that sorts below the highest version
 * already recorded, and that highest version includes rows this build does
 * not ship. Two real histories put shipped migrations below it. A database
 * first migrated before V6–V9 shipped recorded V100 before them (Issue 35).
 * A database that ran the retired Postgres messaging transport recorded
 * V200–V202, so V6–V9, V101 and every future V102–V199 sort below V202 there,
 * whatever the numbering rule above says. The store, lock and retired
 * migrations never depend on each other, so Flyway runs with
 * {@code outOfOrder(true)}: such a migration is applied, and reported pending
 * by {@code VALIDATE}, even though it sorts below the highest applied version.
 * Without it, {@code flyway} mode fails startup on both histories ("Detected
 * resolved migration not applied to database"). Never remove it;
 * {@code MaestroSchemaInitializerTest} pins both histories.
 *
 * <h2>Sharing a schema with the host</h2>
 * <p>The host's tables (and the host's {@code flyway_schema_history}) are
 * normally already present in the schema when Maestro first runs. Flyway
 * refuses to migrate a non-empty schema that has no history table of its own
 * unless it is told to baseline, so this Flyway baselines at version
 * {@code 0} — below every Maestro migration, so all of them are still applied.
 * The baseline only writes a marker row into {@value #HISTORY_TABLE}; it never
 * touches existing tables.
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are immutable and {@link #initialize()} may be called from any
 * thread. Concurrent runs against the same database — several nodes starting
 * at once — are serialised by Flyway's own database lock, so each migration is
 * applied exactly once.
 */
public final class MaestroSchemaInitializer {

    /** Maestro's Flyway history table — never the host's {@code flyway_schema_history}. */
    public static final String HISTORY_TABLE = "maestro_schema_history";

    /** Classpath resource, one per module, listing that module's migration locations. */
    public static final String LOCATIONS_RESOURCE = "META-INF/maestro/schema-locations";

    /** Baseline version for a non-empty schema: below every Maestro migration. */
    private static final String BASELINE_VERSION = "0";

    private static final Logger log = LoggerFactory.getLogger(MaestroSchemaInitializer.class);

    private final DataSource dataSource;
    private final SchemaMode mode;
    private final ClassLoader classLoader;

    /**
     * @param dataSource  the database holding Maestro's tables
     * @param mode        what {@link #initialize()} does
     * @param classLoader the loader whose {@value #LOCATIONS_RESOURCE} resources
     *                    and migration scripts are used
     */
    public MaestroSchemaInitializer(DataSource dataSource, SchemaMode mode, ClassLoader classLoader) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /**
     * @return the mode {@link #initialize()} acts on
     */
    public SchemaMode mode() {
        return mode;
    }

    /**
     * Applies, validates or skips Maestro's migrations according to the mode.
     *
     * @throws IllegalStateException in {@link SchemaMode#VALIDATE} mode when a
     *                               migration is pending, or when no
     *                               {@value #LOCATIONS_RESOURCE} resource is on
     *                               the classpath
     * @throws org.flywaydb.core.api.FlywayException if Flyway fails to migrate
     *                               or validate
     */
    public void initialize() {
        if (mode == SchemaMode.NONE) {
            log.info("maestro.store.schema.mode=none: Maestro is not managing its schema; "
                    + "the maestro_* tables must already exist and be current");
            return;
        }
        var locations = discoverLocations(classLoader);
        if (locations.isEmpty()) {
            throw new IllegalStateException("No " + LOCATIONS_RESOURCE
                    + " resource found on the classpath; Maestro's migrations cannot be located");
        }
        var flyway = Flyway.configure(classLoader)
                .dataSource(dataSource)
                .table(HISTORY_TABLE)
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(true)
                .baselineVersion(BASELINE_VERSION)
                // outOfOrder is load-bearing; never remove it. Flyway refuses a
                // resolved migration that sorts below the highest version already
                // recorded ("Detected resolved migration not applied to
                // database"), and that highest version includes rows this build
                // does not ship. Two real histories hit this. (1) Issue 35: a
                // database first migrated before V6-V9 shipped recorded V100
                // first, so V6-V9 sort below it. (2) A database that ran the
                // retired Postgres messaging transport recorded V200-V202, so V6-V9,
                // V101 and every future V102-V199 sort below V202. Store, lock and
                // the retired band never depend on each other, so out-of-order
                // application is the intended semantics. It also makes such a
                // migration "pending" to info(), so VALIDATE names it below.
                // MaestroSchemaInitializerTest pins both histories.
                .outOfOrder(true)
                .load();

        if (mode == SchemaMode.FLYWAY) {
            var result = flyway.migrate();
            log.info("Maestro schema: applied {} migration(s) from {} into {} (now at version {})",
                    result.migrationsExecuted, locations, HISTORY_TABLE,
                    result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion);
            return;
        }

        // VALIDATE. Flyway's validate() would itself reject pending migrations,
        // but with a generic message; check first so the operator is told
        // exactly which versions are missing and how to apply them.
        var pending = Arrays.stream(flyway.info().pending())
                .map(MigrationInfo::getVersion)
                .map(Object::toString)
                .toList();
        if (!pending.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "Maestro schema is behind: pending migrations %s. Apply them "
                            + "(maestro.store.schema.mode=flyway) or run the SQL under %s yourself.",
                    pending, locations));
        }
        flyway.validate();
        log.info("Maestro schema validated against {} ({})", HISTORY_TABLE, locations);
    }

    /**
     * Reads every {@value #LOCATIONS_RESOURCE} resource visible to the class
     * loader: one Flyway location per non-blank line.
     *
     * @param cl the class loader to search
     * @return the locations, de-duplicated and sorted
     * @throws UncheckedIOException if a resource cannot be read
     */
    public static List<String> discoverLocations(ClassLoader cl) {
        var locations = new TreeSet<String>();
        try {
            var resources = cl.getResources(LOCATIONS_RESOURCE);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                try (var reader = new BufferedReader(
                        new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    reader.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(locations::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + LOCATIONS_RESOURCE, e);
        }
        return List.copyOf(locations);
    }
}
