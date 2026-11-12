package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code WorkflowContext.currentTime()} returns the read-back of the
 * {@code Instant} it recorded — the value a replay of that slot reads (WP1
 * D7, Task 4) — and what it records does not change. Before the fix the live
 * path returned the raw clock value while replay deserialized the stored one,
 * so a host mapper that writes timestamps as epoch milliseconds handed
 * workflow code nanoseconds live and milliseconds after recovery.
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

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;

    @AfterEach
    void tearDown() {
        if (store instanceof FaultyStore faulty) {
            faulty.heal();
        }
        executor.shutdown();
    }

    private void executor(ObjectMapper mapper, VersionedInMemoryStore withStore) {
        store = withStore;
        serializer = new PayloadSerializer(mapper);
        observer = new RecordingEngineObserver();
        executor = WorkflowExecutor.builder(store, serializer, "svc")
                .instantSource(InstantSource.fixed(NOW))
                .observer(observer)
                .build();
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
        var writesAnObject = JsonMapper.builder()
                .addModule(new SimpleModule().addSerializer(Instant.class, new ValueSerializer<Instant>() {
                    @Override
                    public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
                        gen.writeStartObject();
                        gen.writeStringProperty("at", value.toString());
                        gen.writeEndObject();
                    }
                }))
                .build();
        executor(writesAnObject, new VersionedInMemoryStore());

        start("ct-unreadable");

        assertEquals(WorkflowStatus.COMPLETED, awaitStatus("ct-unreadable"));
        assertEquals("2026-11-12T10:00:00.123456789Z", result("ct-unreadable"));
        assertEquals("{\"at\":\"2026-11-12T10:00:00.123456789Z\"}",
                String.valueOf(sideEffectPayload("ct-unreadable")));
        assertEquals(List.of(), observer.failed());
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
}
