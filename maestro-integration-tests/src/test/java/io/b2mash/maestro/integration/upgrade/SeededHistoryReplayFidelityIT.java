package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.engine.ArgumentFingerprint;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Invocations;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedTypedActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedTypedResultWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.TypedRequest;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static io.b2mash.maestro.core.model.WorkflowStatus.COMPLETED;
import static io.b2mash.maestro.core.model.WorkflowStatus.WAITING_SIGNAL;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.WP1_DECIMAL;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.WP1_OFFSET;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.WP1_SET;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WP1 (replay fidelity) over the history the build at {@code cbf5a2a} wrote
 * ({@link SeededHistory}): the dormant {@code seed-wp1-*} runs, which recorded
 * a typed activity result and parked on {@code go} before handing it to a
 * second activity, complete on this build; and a run this build starts writes
 * the very bytes and fingerprint {@code cbf5a2a} wrote for the same result.
 *
 * <p>What it guards:
 * <ul>
 *   <li>The decimal {@code 12345678901234567890.123456789} {@code cbf5a2a}
 *       stored reaches {@code settleAmount} exactly — the JDBC store's exact
 *       decimal reader (C1). Before it, the replayed value was the nearest
 *       {@code double}.</li>
 *   <li>The {@code +02:00} stamp {@code cbf5a2a} stored replays in UTC, and the
 *       unordered set as a set: rule 12's "as the store gives it back". A run
 *       this build starts settles with the same values, because its live
 *       result is the read-back (D7) — not the activity's own object.</li>
 *   <li>What is written is unchanged (plan D-3): the first
 *       {@code ACTIVITY_COMPLETED} of a run started here has the payload text
 *       and {@code args_hash} {@code cbf5a2a} wrote — the {@code OFFSET} text
 *       still carries {@code +02:00} although the value the run gets is
 *       UTC.</li>
 * </ul>
 *
 * <p>A new class, as {@link SeededHistory} asks: {@code SeededHistoryUpgradeIT}'s
 * pins are untouched. The method names are the WP1 plan's, which other
 * documents cite.
 */
@Tag("integration")
@DisplayName("Seeded history written by cbf5a2a, under WP1's replay fidelity")
class SeededHistoryReplayFidelityIT {

    private static final Duration BOUND = Duration.ofSeconds(60);

    /** The three dormant runs, in the order {@code cbf5a2a} started them. */
    private static final List<String> DORMANT = List.of(WP1_DECIMAL, WP1_OFFSET, WP1_SET);

    /** {@code TypedRequest.kind} → the dormant run {@code cbf5a2a} started with it. */
    private static final Map<String, String> SEED_OF_KIND =
            Map.of("DECIMAL", WP1_DECIMAL, "OFFSET", WP1_OFFSET, "SET", WP1_SET);

    /** The recorded step and the settling step of each dormant run. */
    private static final Map<String, List<String>> STEPS = Map.of(
            WP1_DECIMAL, List.of("seed-typed.exactAmount", "seed-typed.settleAmount"),
            WP1_OFFSET, List.of("seed-typed.localStamp", "seed-typed.settleStamp"),
            WP1_SET, List.of("seed-typed.tags", "seed-typed.settleTags"));

    /**
     * Each run's output on this build: the settling activity's result over the
     * value the first step's payload reads back as — the exact decimal, the
     * stamp in UTC, the sorted tags.
     */
    private static final Map<String, String> SETTLED = Map.of(
            WP1_DECIMAL, "amount:12345678901234567890.123456789",
            WP1_OFFSET, "stamp:2026-11-12T08:00Z",
            WP1_SET, "tags:alpha,beta,delta,gamma");

    /** The decimal {@code cbf5a2a} stored as {@code exactAmount}'s result. */
    private static final BigDecimal EXACT = new BigDecimal("12345678901234567890.123456789");

    /** Set by the {@code seededHistoryNonUtcTest} Gradle task to the zone its JVM runs in. */
    private static final String EXPECTED_ZONE_PROPERTY = "maestro.test.expectedZone";

    /**
     * Under {@code seededHistoryNonUtcTest} this class runs in a non-UTC
     * default zone; this proves it here rather than relying on
     * {@code SeededHistoryUpgradeIT}'s check of the same JVM. Skipped in the
     * ordinary {@code test} task, which sets no expected zone.
     */
    @Test
    @DisplayName("under seededHistoryNonUtcTest the JVM runs in the zone the task asks for, which is not UTC")
    void theJvmRunsInTheZoneTheNonUtcTaskAsksFor() {
        var expected = System.getProperty(EXPECTED_ZONE_PROPERTY);
        assumeTrue(expected != null, EXPECTED_ZONE_PROPERTY + " is set only by the seededHistoryNonUtcTest task");
        assertEquals(ZoneId.of(expected), ZoneId.systemDefault(),
                "JVM default zone (user.timezone=" + System.getProperty("user.timezone") + ")");
        assertNotEquals(ZoneOffset.UTC, ZoneId.systemDefault().getRules().getOffset(Instant.now()));
    }

    @Test
    @DisplayName("the dormant seed-wp1-* runs complete on this build, each settling with the value cbf5a2a recorded as it reads back")
    void dormantTypedRunsWrittenAtCbf5a2aCompleteOnThisBuild() throws Exception {
        var seeded = SeededHistory.load().upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
        var observer = new RecordingObserver();
        var invocations = new Invocations();
        var before = new LinkedHashMap<String, List<Row>>();
        for (var id : DORMANT) {
            before.put(id, rows(seeded, id));
        }

        try (var node = seeded.node(Seeds.WP1_SERVICE, observer, invocations)) {
            assertEquals(3, node.recover(), "recovery adopts exactly the three dormant WP1 runs");
            for (var id : DORMANT) {
                node.deliverSignal(id, "go", "go");
            }
            for (var id : DORMANT) {
                assertEquals(COMPLETED, seeded.awaitStatusOrLast(id, COMPLETED, BOUND),
                        () -> id + "; stand-downs: " + observer.standDowns());
            }
        }

        assertAll(
                () -> assertEquals(List.of(), observer.standDowns(), "nothing stood down"),
                () -> assertEquals(List.of(), observer.abandoned(), "nothing was abandoned"),
                () -> assertEquals(List.of(), observer.failed(), "nothing failed"),
                () -> assertEquals(Map.of(
                                "seed-typed.exactAmount", 0, "seed-typed.localStamp", 0, "seed-typed.tags", 0,
                                "seed-typed.settleAmount", 1, "seed-typed.settleStamp", 1, "seed-typed.settleTags", 1),
                        typedCounts(invocations), "recorded steps replayed, never re-run; each settle ran live once"));
        // The exact decimal written at cbf5a2a reached the activity — not the nearest double. Against a
        // literal, so it does not depend on this build's reader.
        assertEquals(ArgumentFingerprint.of(seeded.serializer(), new Object[]{EXACT}),
                only(rows(seeded, WP1_DECIMAL), "seed-typed.settleAmount").argsHash(),
                "settleAmount's args_hash: the fingerprint of 12345678901234567890.123456789");

        for (var id : DORMANT) {
            var after = rows(seeded, id);
            var recorded = before.get(id);
            var settle = STEPS.get(id).get(1);
            assertAll(id,
                    () -> assertEquals(recorded, after.subList(0, recorded.size()),
                            "history cbf5a2a wrote is untouched"),
                    () -> assertEquals(List.of(
                                    "3 SIGNAL_RECEIVED $maestro:awaitSignal:go",
                                    "4 ACTIVITY_COMPLETED " + settle,
                                    "5 WORKFLOW_COMPLETED null"),
                            after.subList(recorded.size(), after.size()).stream().map(Row::describe).toList(),
                            "events this build appended"),
                    () -> assertEquals(SETTLED.get(id), output(seeded, id), "the run's output"),
                    () -> assertEquals(expectedSettleHash(seeded, id, STEPS.get(id).get(0)),
                            only(after, settle).argsHash(),
                            "the settle step's args_hash: the fingerprint of the first step's read-back"));
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"DECIMAL", "OFFSET", "SET"})
    @DisplayName("a run started on this build writes the result cbf5a2a wrote, byte for byte, and settles as the seeded run does")
    void thisBuildWritesTheBytesCbf5a2aWrote(String kind) throws Exception {
        var seeded = SeededHistory.load().upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
        var seed = SEED_OF_KIND.get(kind);
        var steps = STEPS.get(seed);
        var seededFirst = only(rows(seeded, seed), steps.get(0));
        var fresh = "wp1-fresh-" + kind.toLowerCase();
        var observer = new RecordingObserver();
        var invocations = new Invocations();

        Row freshFirst;
        try (var node = seeded.node(Seeds.WP1_SERVICE, observer, invocations)) {
            node.start(fresh, SeedTypedResultWorkflow.class, new TypedRequest(kind));
            assertEquals(WAITING_SIGNAL, seeded.awaitStatusOrLast(fresh, WAITING_SIGNAL, BOUND),
                    () -> fresh + "; stand-downs: " + observer.standDowns());
            freshFirst = only(rows(seeded, fresh), steps.get(0));
            node.deliverSignal(fresh, "go", "go");
            assertEquals(COMPLETED, seeded.awaitStatusOrLast(fresh, COMPLETED, BOUND),
                    () -> fresh + "; stand-downs: " + observer.standDowns());
        }

        var settle = only(rows(seeded, fresh), steps.get(1));
        assertAll(kind,
                () -> assertEquals(seededFirst.describe(), freshFirst.describe(), "sequence, type and step"),
                () -> assertEquals(comparablePayload(kind, seededFirst), comparablePayload(kind, freshFirst),
                        "the stored result, as Postgres renders it"),
                () -> assertEquals(seededFirst.argsHash(), freshFirst.argsHash(), "the first step's args_hash"),
                () -> assertEquals(SETTLED.get(seed), output(seeded, fresh),
                        "settles with the value the seeded run settles with"),
                // Against the run's OWN first payload: a HashSet's iteration order — and so a set's
                // fingerprint — follows the stored element order when two elements share a bucket
                // ("beta" and "gamma" do), and Set.of writes a per-JVM order.
                () -> assertEquals(expectedSettleHash(seeded, fresh, steps.get(0)), settle.argsHash(),
                        "the settle step's args_hash: the fingerprint of the first step's read-back"),
                () -> assertEquals(List.of(), observer.standDowns(), "nothing stood down"));
        if (kind.equals("OFFSET")) {
            // the write path is unchanged although the run's value is UTC
            assertEquals("\"2026-11-12T10:00:00+02:00\"", freshFirst.payload());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** One event row, read by raw SQL: Postgres's own rendering of the payload. */
    private record Row(int seq, String type, @Nullable String step, @Nullable String payload,
                       @Nullable String argsHash) {

        String describe() {
            return seq + " " + type + " " + step;
        }
    }

    /** Every event of the workflow, in sequence order, by raw SQL ({@code payload::text}). */
    private static List<Row> rows(SeededHistory seeded, String workflowId) throws SQLException {
        var rows = new ArrayList<Row>();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("""
                     SELECT e.sequence_number, e.event_type, e.step_name, e.payload::text, e.args_hash
                     FROM maestro_workflow_event e
                     JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id
                     WHERE i.workflow_id = ?
                     ORDER BY e.sequence_number""")) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5)));
                }
            }
        }
        return rows;
    }

    /** The one row of {@code step}. */
    private static Row only(List<Row> rows, String step) {
        var matching = rows.stream().filter(r -> step.equals(r.step())).toList();
        assertEquals(1, matching.size(), () -> step + " in " + rows);
        return matching.getFirst();
    }

    /**
     * The fingerprint the settling step of {@code seedId} must carry: its
     * argument is the first step's result as a replay reads it — the stored
     * payload, read through this build's store, deserialized into the frozen
     * method's generic return type.
     */
    private static String expectedSettleHash(SeededHistory seeded, String workflowId, String firstStep)
            throws Exception {
        var first = seeded.events(workflowId).stream()
                .filter(e -> firstStep.equals(e.stepName())).findFirst().orElseThrow();
        var method = SeedTypedActivities.class.getMethod(firstStep.substring("seed-typed.".length()));
        var value = seeded.serializer().deserialize(first.payload(), method.getGenericReturnType());
        if (firstStep.equals("seed-typed.localStamp")) {
            assertEquals(OffsetDateTime.parse("2026-11-12T08:00Z"), value, "the +02:00 stamp reads back in UTC");
        }
        return Objects.requireNonNull(ArgumentFingerprint.of(seeded.serializer(), new Object[]{value}), workflowId);
    }

    /** The run's output, a {@code String}. */
    private static @Nullable String output(SeededHistory seeded, String workflowId) {
        var instance = seeded.store().getInstance(workflowId).orElseThrow();
        assertNotNull(instance.output(), () -> workflowId + " has no output");
        return seeded.serializer().deserialize(instance.output(), String.class);
    }

    /**
     * The payload as compared across builds: Postgres's text for DECIMAL and
     * OFFSET; for SET the sorted elements, because {@code Set.of}'s iteration
     * order — the order written — varies from JVM to JVM.
     */
    private static Object comparablePayload(String kind, Row row) {
        var text = Objects.requireNonNull(row.payload(), row::describe);
        if (!kind.equals("SET")) {
            return text;
        }
        return JsonMapper.builder().build().readValue(text, new TypeReference<List<String>>() {}).stream()
                .sorted().toList();
    }

    /** The {@code seed-typed.*} entries of the invocation counts. */
    private static Map<String, Integer> typedCounts(Invocations invocations) {
        return invocations.snapshot().entrySet().stream()
                .filter(e -> e.getKey().startsWith("seed-typed."))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
