package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.logcapture.CapturingSlf4jProvider;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code WorkflowContext.currentTime()} returns the read-back of the
 * {@code Instant} it recorded — the value a replay of that slot reads (WP1
 * D7, Task 4) — and what it records does not change. Before the fix the live
 * path returned the raw clock value while replay deserialized the stored one,
 * so a host mapper that writes and reads timestamps as epoch milliseconds
 * (both nanosecond features off) handed workflow code nanoseconds live and
 * milliseconds after recovery.
 *
 * <p>Two mappers keep the clock value on the live run. One that
 * <em>throws</em> reading back its own {@code Instant} takes the "return the
 * clock value" fallback (logged at DEBUG; a replay stands down). One that
 * reads its own write back as a <em>wrong</em> instant without throwing —
 * timestamps written ({@code WRITE_DATES_AS_TIMESTAMPS} on) as epoch
 * milliseconds ({@code WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS} off) while
 * integer timestamps are still read as seconds
 * ({@code READ_DATE_TIMESTAMPS_AS_NANOSECONDS} on, its default), so 2026
 * reads as the year 58834 — is caught by the plausibility check: a read-back
 * more than one second from the recorded clock value is discarded for the
 * clock value, with one WARN per {@link PayloadSerializer}. Live budgets
 * ({@code collectSignals}, {@code retryUntil}) are then computed from the
 * real clock. A replay still reads the stored value as that mapper reads it,
 * the misread instant, as on the released build.
 *
 * <p>Every assertion is on a collected value: the instance row's output, the
 * event log, the observer's lists.
 */
class CurrentTimeReadBackTest {

    private static final Duration BOUND = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00.123456789Z");

    /**
     * Writes an {@code Instant} as epoch milliseconds and reads an integer
     * timestamp as milliseconds — the host configuration that truncates.
     * Jackson reads integer timestamps as epoch <em>seconds</em> while
     * {@code READ_DATE_TIMESTAMPS_AS_NANOSECONDS} is on (its default), so a
     * mapper that writes millis must turn that off to read its own writes.
     */
    private static final ObjectMapper MILLIS_MAPPER = JsonMapper.builder()
            .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .disable(DateTimeFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .build();

    /**
     * Writes an {@code Instant} as epoch milliseconds but leaves
     * {@code READ_DATE_TIMESTAMPS_AS_NANOSECONDS} on (Jackson's default), so it
     * reads its own integer timestamps as epoch <em>seconds</em>: a value
     * about a thousand times too far from the epoch, with no exception.
     */
    private static final ObjectMapper ASYMMETRIC_MILLIS_MAPPER = JsonMapper.builder()
            .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .build();

    /** Writes an {@code Instant} as an object it cannot read back as an {@code Instant}. */
    private static final ObjectMapper WRITES_AN_OBJECT = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(Instant.class, new ValueSerializer<Instant>() {
                @Override
                public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
                    gen.writeStartObject();
                    gen.writeStringProperty("at", value.toString());
                    gen.writeEndObject();
                }
            }))
            .build();

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;
    private @Nullable WorkflowExecutor recovering;

    @AfterEach
    void tearDown() {
        if (store instanceof FaultyStore faulty) {
            faulty.heal();
        }
        executor.shutdown();
        if (recovering != null) {
            recovering.shutdown();
        }
    }

    private void executor(ObjectMapper mapper, VersionedInMemoryStore withStore) {
        executor(mapper, withStore, InstantSource.fixed(NOW));
    }

    private void executor(ObjectMapper mapper, VersionedInMemoryStore withStore, InstantSource clock) {
        store = withStore;
        serializer = new PayloadSerializer(mapper);
        observer = new RecordingEngineObserver();
        executor = WorkflowExecutor.builder(store, serializer, "svc")
                .instantSource(clock)
                .observer(observer)
                .build();
    }

    /** A clock that starts at {@code start} and moves {@code step} forward on every read. */
    private static InstantSource ticking(Instant start, Duration step) {
        var reads = new java.util.concurrent.atomic.AtomicLong();
        return () -> start.plus(step.multipliedBy(reads.getAndIncrement()));
    }

    @Test
    void currentTimeIsTheRecordedValue_whenTheMapperWritesMillis() throws Exception {
        executor(MILLIS_MAPPER, new VersionedInMemoryStore());

        start("ct-millis");

        assertEquals(WorkflowStatus.COMPLETED, awaitStatus("ct-millis"));
        assertEquals("2026-11-12T10:00:00.123Z", result("ct-millis"));
    }

    @Test
    void currentTimeWritesTheSamePayloadAsBefore() throws Exception {
        executor(MILLIS_MAPPER, new VersionedInMemoryStore());

        start("ct-payload");

        assertEquals(WorkflowStatus.COMPLETED, awaitStatus("ct-payload"));
        assertEquals(serializer.serialize(NOW), sideEffectPayload("ct-payload"));
    }

    @Test
    void defaultMapperIsUnaffected() throws Exception {
        executor(JsonMapper.builder().build(), new VersionedInMemoryStore());

        start("ct-default");

        assertEquals(WorkflowStatus.COMPLETED, awaitStatus("ct-default"));
        assertEquals("2026-11-12T10:00:00.123456789Z", result("ct-default"));
    }

    /**
     * A host mapper that cannot read the {@code Instant} it wrote never fails
     * the run (D9's principle): the live call returns the raw clock value and
     * the side effect stays durable as written. A replay of that slot stands
     * down as {@code UNKNOWN_EVENT_PAYLOAD}, which {@code maestro.standdown}
     * already counts.
     */
    @Test
    void unreadableReadBack_returnsTheRawValueAndNeverFailsTheRun() throws Exception {
        executor(WRITES_AN_OBJECT, new VersionedInMemoryStore());

        try (var logs = CapturingSlf4jProvider.watch(DefaultWorkflowOperations.class)) {
            start("ct-unreadable");
            assertEquals(WorkflowStatus.COMPLETED, awaitStatus("ct-unreadable"));

            // One DEBUG line names the workflow, the slot and the outcome — and no recorded value.
            var lines = logs.records().stream()
                    .filter(r -> r.message().contains("ct-unreadable") && r.message().contains("currentTime()"))
                    .toList();
            assertEquals(List.of(Level.DEBUG), lines.stream().map(CapturingSlf4jProvider.CapturedLog::level).toList(),
                    lines.toString());
            var line = lines.getFirst().message();
            assertTrue(line.contains("sequence 1") && line.contains("unknown_event_payload")
                    && line.contains("tools.jackson.databind.exc.MismatchedInputException"), line);
            assertFalse(line.contains("2026-11-12"), line);
        }
        assertEquals("2026-11-12T10:00:00.123456789Z", result("ct-unreadable"));
        assertEquals("{\"at\":\"2026-11-12T10:00:00.123456789Z\"}",
                String.valueOf(sideEffectPayload("ct-unreadable")));
        assertEquals(List.of(), observer.failed());
    }

    /** The stand-down the fallback's comment promises: a replay of that slot cannot read it either. */
    @Test
    void unreadableReadBack_aReplayOfTheSlotStandsDownAsUnknownEventPayload() throws Exception {
        executor(WRITES_AN_OBJECT, new VersionedInMemoryStore());
        var method = ParkedStampWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow("ct-unreadable-replay", "ParkedStampWorkflow", "default", "in",
                ParkedStampWorkflow::new, method);
        await().atMost(BOUND).until(() -> store.getInstance("ct-unreadable-replay")
                .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));

        // The node stops while the run is parked; a second node adopts it and replays currentTime().
        executor.shutdown();
        var recoveringObserver = new RecordingEngineObserver();
        recovering = WorkflowExecutor.builder(store, serializer, "svc").instantSource(InstantSource.fixed(NOW))
                .observer(recoveringObserver).build();
        recovering.recoverWorkflows(Map.of("ParkedStampWorkflow", new WorkflowRegistration(
                "ParkedStampWorkflow", "default", ParkedStampWorkflow::new, method)));
        await().atMost(BOUND).until(() -> !recoveringObserver.standDowns().isEmpty()
                || store.getInstance("ct-unreadable-replay").orElseThrow().status().isTerminal());

        assertEquals(List.of(StandDownReason.UNKNOWN_EVENT_PAYLOAD),
                recoveringObserver.standDownReasons("ct-unreadable-replay"));
        assertEquals(WorkflowStatus.WAITING_SIGNAL, store.getInstance("ct-unreadable-replay").orElseThrow().status());
        assertEquals(List.of(), recoveringObserver.failed());
    }

    /**
     * A mapper that reads its own write back <em>wrong</em>, without throwing:
     * the read-back is more than a second from the clock value, so the live
     * run gets the clock value, and one WARN (per serializer) says the mapper
     * does not read back the timestamp it wrote. What is recorded is unchanged.
     */
    @Test
    void asymmetricMillisMapper_liveRunGetsTheClockValue_andWarnsOncePerSerializer() throws Exception {
        executor(ASYMMETRIC_MILLIS_MAPPER, new VersionedInMemoryStore());

        try (var logs = CapturingSlf4jProvider.watch(DefaultWorkflowOperations.class)) {
            start("ct-asymmetric");
            assertEquals(WorkflowStatus.COMPLETED, awaitStatus("ct-asymmetric"));
            start("ct-asymmetric-2");
            assertEquals(WorkflowStatus.COMPLETED, awaitStatus("ct-asymmetric-2"));

            var lines = logs.records().stream()
                    .filter(r -> r.message().contains("does not read back the timestamp it wrote"))
                    .toList();
            assertEquals(List.of(Level.WARN, Level.DEBUG),
                    lines.stream().map(CapturingSlf4jProvider.CapturedLog::level).toList(), lines.toString());
            var warn = lines.getFirst().message();
            assertEquals(DefaultWorkflowOperations.timestampMisreadWarning("ct-asymmetric", 1,
                    Duration.between(NOW, Instant.parse("+58834-09-24T16:02:03Z"))), warn);
            assertTrue(warn.contains("live and replayed times will differ"), warn);
            assertFalse(warn.contains("2026") || warn.contains("58834") || warn.contains("1794477600123"), warn);
        }
        assertEquals("1794477600123", String.valueOf(sideEffectPayload("ct-asymmetric")));   // epoch millis, as before
        assertEquals(NOW.toString(), result("ct-asymmetric"));                              // the clock, not year 58834
        assertEquals(NOW.toString(), result("ct-asymmetric-2"));
        assertEquals(List.of(), observer.failed());
    }

    /**
     * The live budget of {@code collectSignals} is computed from the clock
     * value, not from a misread ~1000× too far from the epoch. The clock ticks
     * one second per read: from misread instants the ten-minute budget would
     * be gone (1000 s) before the first await, and the run would fail with a
     * {@code SignalTimeoutException}.
     */
    @Test
    void asymmetricMillisMapper_collectSignalsBudgetIsNotExhaustedEarly() throws Exception {
        executor(ASYMMETRIC_MILLIS_MAPPER, new VersionedInMemoryStore(), ticking(NOW, Duration.ofSeconds(1)));
        var id = "ct-asymmetric-collect";
        executor.startWorkflow(id, "CollectWorkflow", "default", "in",
                CollectWorkflow::new, CollectWorkflow.class.getMethod("run", String.class));
        await().atMost(BOUND).until(() -> store.getInstance(id)
                .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL || i.status().isTerminal()).orElse(false));
        executor.deliverSignal(id, "item", "first");

        assertEquals(WorkflowStatus.COMPLETED, awaitStatus(id), "failed: " + observer.failed());
        assertEquals("[first]", result(id));
    }

    /**
     * After a recovery the asymmetric mapper's replay reads the stored millis
     * as seconds, as on the released build: the live run saw the clock value,
     * the replayed run sees the misread instant — what the WARN says.
     */
    @Test
    void asymmetricMillisMapper_aReplayReadsTheMisreadInstant() throws Exception {
        executor(ASYMMETRIC_MILLIS_MAPPER, new VersionedInMemoryStore());
        var id = "ct-asymmetric-replay";
        var seen = java.util.Collections.synchronizedList(new java.util.ArrayList<Instant>());
        var method = RecordingParkedStampWorkflow.class.getMethod("run", String.class);
        executor.startWorkflow(id, "RecordingParkedStampWorkflow", "default", "in",
                () -> new RecordingParkedStampWorkflow(seen), method);
        await().atMost(BOUND).until(() -> store.getInstance(id)
                .map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));

        executor.shutdown();
        recovering = WorkflowExecutor.builder(store, serializer, "svc").instantSource(InstantSource.fixed(NOW))
                .observer(new RecordingEngineObserver()).build();
        recovering.recoverWorkflows(Map.of("RecordingParkedStampWorkflow", new WorkflowRegistration(
                "RecordingParkedStampWorkflow", "default", () -> new RecordingParkedStampWorkflow(seen), method)));
        await().atMost(BOUND).until(() -> seen.size() == 2);
        recovering.deliverSignal(id, "go", "go");
        await().atMost(BOUND).until(() -> store.getInstance(id).orElseThrow().status().isTerminal());

        assertEquals(List.of(NOW, Instant.parse("+58834-09-24T16:02:03Z")), List.copyOf(seen), "[live, replayed]");
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance(id).orElseThrow().status());
    }

    /** A store fault on the side-effect append keeps today's handling: latched, never failed. */
    @Test
    void sideEffectAppendOutage_latchesStoreUnavailable() throws Exception {
        var faulty = new FaultyStore();
        faulty.failWhen("appendEvent", args -> ((WorkflowEvent) args[0]).eventType() == EventType.SIDE_EFFECT);
        executor(MILLIS_MAPPER, faulty);

        start("ct-outage");
        await().atMost(BOUND).until(() -> !executor.isRunning("ct-outage"));

        assertEquals(List.of(AbandonReason.STORE_UNAVAILABLE), observer.abandoned().stream()
                .filter(a -> a.info().workflowId().equals("ct-outage"))
                .map(RecordingEngineObserver.AbandonCall::reason).toList());
        assertEquals(WorkflowStatus.RUNNING, store.getInstance("ct-outage").orElseThrow().status());
        assertEquals(List.of(), observer.failed());
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private UUID start(String id) throws NoSuchMethodException {
        var workflow = new StampWorkflow();
        return executor.startWorkflow(id, "StampWorkflow", "default", "in",
                () -> workflow, StampWorkflow.class.getMethod("run", String.class));
    }

    private WorkflowStatus awaitStatus(String id) {
        await().atMost(BOUND).until(() -> !executor.isRunning(id));
        return store.getInstance(id).orElseThrow().status();
    }

    private @Nullable String result(String id) {
        return serializer.deserialize(store.getInstance(id).orElseThrow().output(), String.class);
    }

    private @Nullable JsonNode sideEffectPayload(String id) {
        var instanceId = store.getInstance(id).orElseThrow().id();
        var matches = store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.SIDE_EFFECT && "$maestro:currentTime".equals(e.stepName()))
                .toList();
        assertEquals(1, matches.size(), "currentTime side effects of " + id);
        return matches.getFirst().payload();
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    /** Returns {@code currentTime().toString()}. */
    public static class StampWorkflow {
        public String run(String in) {
            return WorkflowContext.current().currentTime().toString();
        }
    }

    /** Collects one {@code "item"} signal within ten minutes and returns what it collected. */
    public static class CollectWorkflow {
        public String run(String in) {
            return WorkflowContext.current().collectSignals("item", String.class, 1, Duration.ofMinutes(10)).toString();
        }
    }

    /** Records {@code currentTime()} (live, then on every replay), then parks on {@code "go"}. */
    public static class RecordingParkedStampWorkflow {
        private final List<Instant> seen;

        RecordingParkedStampWorkflow(List<Instant> seen) {
            this.seen = seen;
        }

        public String run(String in) {
            var now = WorkflowContext.current().currentTime();
            seen.add(now);
            WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(5));
            return now.toString();
        }
    }

    /** Takes {@code currentTime()}, then parks on {@code "go"}. */
    public static class ParkedStampWorkflow {
        public String run(String in) {
            var now = WorkflowContext.current().currentTime();
            WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(5));
            return now.toString();
        }
    }
}
