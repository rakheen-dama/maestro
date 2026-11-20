package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows.Seeds;
import io.b2mash.maestro.store.postgres.schema.SchemaMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.b2mash.maestro.core.model.WorkflowStatus.FAILED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The saga each fixture from {@code v0.3.0-rc.1} on left parked mid-flight ({@code seed-saga-parked}:
 * {@code reserve} and {@code charge} recorded, both compensations registered,
 * parked on {@code ship-now}), resumed by this build: the seed the
 * {@code cbf5a2a} fixture does not hold, so it is tested here rather than in
 * the assertions every fixture shares.
 *
 * <p>"Resumes correctly" here means: recovery adopts the run, the signal
 * delivered on this build is stored and consumed, {@code ship} runs once and
 * fails, the compensation stack the run rebuilt from the recorded steps
 * unwinds in LIFO order — {@code refund}, then {@code releaseReservation},
 * each once — and the run ends {@code FAILED} by its own failure. No recorded
 * step runs again, the history the older build wrote is an unchanged
 * prefix, and nothing stands down. It runs once per fixture of
 * {@link SeededFixtures#all()} that holds the seed.
 */
@Tag("integration")
@DisplayName("A saga an older release left parked mid-flight, under this build")
class SeededParkedSagaIT {

    /** Every fixture that holds {@code seed-saga-parked}: {@code v0_3_0_rc_1} and later. */
    static Stream<SeededFixture> fixturesWithAParkedSaga() {
        return SeededFixtures.all().stream().filter(f -> f.recordedEvents().containsKey(Seeds.SAGA_PARKED));
    }

    @ParameterizedTest(name = "written by {0}")
    @MethodSource("fixturesWithAParkedSaga")
    @DisplayName("the parked saga resumes on this build, fails in ship and compensates both steps once, in LIFO order")
    void theParkedSagaResumesAndCompensates(SeededFixture fixture) throws Exception {
        assertThat(fixture.parkedSagaAppended()).as("%s holds the parked saga, so it pins what this build appends", fixture)
                .isNotEmpty();
        var seeded = SeededHistory.load(fixture).upgrade(SchemaMode.FLYWAY).requireClockWithinFixtureWindow();
        var before = seeded.events(Seeds.SAGA_PARKED);
        assertThat(before.stream().map(SeededParkedSagaIT::describe)).containsExactly(
                "1 ACTIVITY_COMPLETED seed-saga.reserve",
                "2 ACTIVITY_COMPLETED seed-saga.charge",
                "3 TIMER_SCHEDULED $maestro:awaitSignal:ship-now");
        var observer = new RecordingObserver();
        var live = new ArrayList<String>();
        var activities = fixture.activities((step, key) -> {
            synchronized (live) {
                live.add(step + ":" + key);
            }
        }, true);

        try (var node = seeded.node(Seeds.SAGA_SERVICE, observer, activities)) {
            assertThat(node.recover()).as("only the parked saga is in its service").isEqualTo(1);
            node.deliverSignal(Seeds.SAGA_PARKED, Seeds.SHIP_NOW, "now");
            assertThat(seeded.awaitStatusOrLast(Seeds.SAGA_PARKED, FAILED, Duration.ofSeconds(60)))
                    .as("stand-downs: %s", observer.standDowns()).isEqualTo(FAILED);
        }

        assertThat(observer.standDowns()).isEmpty();
        assertThat(observer.abandoned()).isEmpty();
        synchronized (live) {
            assertThat(live).as("ship, then the LIFO unwind; nothing recorded ran again").containsExactly(
                    "seed-saga.ship:parked", "seed-saga.refund:parked", "seed-saga.releaseReservation:parked");
        }
        assertThat(activities.snapshot()).containsAllEntriesOf(Map.of(
                "seed-saga.reserve", 0, "seed-saga.charge", 0, "seed-saga.ship", 1, "seed-saga.refund", 1,
                "seed-saga.releaseReservation", 1));
        var after = seeded.events(Seeds.SAGA_PARKED);
        assertThat(after.subList(0, before.size())).as("history %s wrote is untouched", fixture).isEqualTo(before);
        assertThat(after.subList(before.size(), after.size()).stream().map(SeededParkedSagaIT::describe))
                .as("events this build appended").containsExactlyElementsOf(fixture.parkedSagaAppended());
        var failed = after.getLast();
        assertThat(failed.eventType()).isEqualTo(EventType.WORKFLOW_FAILED);
        assertThat(String.valueOf(failed.payload())).as("failed by its own failure").contains("no carrier for parked");
        assertThat(signalRows(seeded)).containsExactly("ship-now consumed=true");
    }

    private static String describe(WorkflowEvent event) {
        return event.sequenceNumber() + " " + event.eventType() + " " + event.stepName();
    }

    private static List<String> signalRows(SeededHistory seeded) throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = seeded.dataSource().getConnection();
             var ps = conn.prepareStatement("SELECT signal_name, consumed FROM maestro_workflow_signal"
                     + " WHERE workflow_id = ?")) {
            ps.setString(1, Seeds.SAGA_PARKED);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + " consumed=" + rs.getBoolean(2));
                }
            }
        }
        return rows;
    }
}
