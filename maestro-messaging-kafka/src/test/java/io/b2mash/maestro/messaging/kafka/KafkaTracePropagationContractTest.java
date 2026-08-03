package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.core.observe.TraceContextHolder;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Kafka trace-propagation wire contract (observability design doc §4.1–4.3,
 * §4.4), pinned against a real broker.
 *
 * <p>These assertions deliberately pin the <b>header names</b> and the
 * <b>W3C {@code traceparent} grammar</b> rather than "whatever the configured
 * propagator produced": if a future Spring Boot default changed the propagation
 * type, the cross-service contract would break silently for every deployment
 * that upgraded one service before the other. This test fails loudly instead.
 */
@DisplayName("Kafka records carry the W3C trace context contract")
class KafkaTracePropagationContractTest extends KafkaTestSupport {

    /** Design §4.1: the exact, lowercase, ASCII header names. */
    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final String BAGGAGE = "baggage";

    /** Design §4.1: the pinned {@code traceparent} grammar. */
    private static final Pattern TRACEPARENT_GRAMMAR =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final Set<String> ALLOWED_TRACE_HEADERS = Set.of(TRACEPARENT, TRACESTATE, BAGGAGE);

    private OtelTracingFixture otel;
    private KafkaTracePropagation propagation;
    private KafkaWorkflowMessaging traced;
    private KafkaWorkflowMessaging untraced;
    private String testSuffix;

    @BeforeEach
    void setUpPropagation() {
        testSuffix = UUID.randomUUID().toString().substring(0, 8);
        otel = new OtelTracingFixture();
        propagation = new KafkaTracePropagation(otel.tracer(), otel.propagator());
        traced = new KafkaWorkflowMessaging(
                kafkaTemplate, consumerFactory, objectMapper, config(), propagation);
        untraced = new KafkaWorkflowMessaging(
                kafkaTemplate, consumerFactory, objectMapper, config());
    }

    @AfterEach
    void tearDownPropagation() {
        traced.destroy();
        untraced.destroy();
        TraceContextHolder.clear();
        otel.close();
    }

    private KafkaMessagingConfig config() {
        return new KafkaMessagingConfig(
                null, null,
                "maestro.admin.events." + testSuffix,
                "trace-group-" + testSuffix,
                2, Duration.ofMillis(50), 2.0, Duration.ofMillis(200), ".DLT");
    }

    // ── Injection (design §4.1, §4.2) ─────────────────────────────────

    @Test
    @DisplayName("a signal published under an active span carries a grammar-valid traceparent "
            + "naming that span")
    void publishedSignalCarriesTraceparent() throws Exception {
        var service = "svc-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        var span = otel.tracer().nextSpan().name("publish").start();
        String traceId;
        String spanId;
        try (var ignored = otel.tracer().withSpan(span)) {
            traceId = span.context().traceId();
            spanId = span.context().spanId();
            traced.publishSignal(service, new SignalMessage(
                    "order-1", "approval", objectMapper.valueToTree("granted")));
        } finally {
            span.end();
        }

        var record = readOne(topic);
        var traceparent = header(record, TRACEPARENT);
        assertNotNull(traceparent, "every published record must carry a traceparent header");
        assertTrue(TRACEPARENT_GRAMMAR.matcher(traceparent).matches(),
                () -> "traceparent '" + traceparent + "' violates the W3C grammar");
        assertEquals(traceId, traceparent.substring(3, 35), "trace-id field");
        assertEquals(spanId, traceparent.substring(36, 52), "span-id field");

        assertTrue(ALLOWED_TRACE_HEADERS.containsAll(traceHeaderNames(record)),
                () -> "unexpected trace header(s): " + traceHeaderNames(record));
    }

    @Test
    @DisplayName("tasks and lifecycle events are injected on the same contract")
    void publishedTaskAndLifecycleEventCarryTraceparent() throws Exception {
        var queue = "q-" + testSuffix;
        var taskTopic = "maestro.tasks." + queue;
        var adminTopic = "maestro.admin.events." + testSuffix;
        createTopics(taskTopic, adminTopic);

        var span = otel.tracer().nextSpan().name("publish").start();
        try (var ignored = otel.tracer().withSpan(span)) {
            traced.publishTask(queue, new TaskMessage(
                    UUID.randomUUID(), "order-1", "OrderWorkflow", UUID.randomUUID(), "svc", null));
            traced.publishLifecycleEvent(new WorkflowLifecycleEvent(
                    UUID.randomUUID(), "order-1", "OrderWorkflow", "svc", queue,
                    LifecycleEventType.WORKFLOW_STARTED, null, null, Instant.now()));
        } finally {
            span.end();
        }

        var taskparent = header(readOne(taskTopic), TRACEPARENT);
        assertNotNull(taskparent, "publishTask must inject");
        assertTrue(TRACEPARENT_GRAMMAR.matcher(taskparent).matches());

        var adminparent = header(readOne(adminTopic), TRACEPARENT);
        assertNotNull(adminparent, "publishLifecycleEvent must inject");
        assertTrue(TRACEPARENT_GRAMMAR.matcher(adminparent).matches());
    }

    @Test
    @DisplayName("with no active span, no trace headers are written at all")
    void publishWithoutActiveSpanWritesNoTraceHeaders() throws Exception {
        var service = "svc-nospan-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        traced.publishSignal(service, new SignalMessage("order-1", "approval", null));

        assertTrue(traceHeaderNames(readOne(topic)).isEmpty(),
                "an untraced publish must not fabricate a trace context");
    }

    @Test
    @DisplayName("without the propagation collaborator the wire format is byte-identical to today")
    void publishWithoutCollaboratorWritesNoTraceHeaders() throws Exception {
        var service = "svc-plain-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        var span = otel.tracer().nextSpan().name("publish").start();
        try (var ignored = otel.tracer().withSpan(span)) {
            untraced.publishSignal(service, new SignalMessage("order-1", "approval", null));
        } finally {
            span.end();
        }

        assertTrue(traceHeaderNames(readOne(topic)).isEmpty(),
                "a build with no tracing beans must produce exactly the pre-tracing wire format");
    }

    // ── Extraction (design §4.3(a)) ───────────────────────────────────

    @Test
    @DisplayName("a consumed signal restores the remote context and the TraceContextHolder "
            + "for the handler")
    void consumedSignalRestoresRemoteContextAndHolder() throws Exception {
        var service = "svc-rt-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        var seenTraceId = new AtomicReference<String>();
        var seenHolder = new AtomicReference<String>();
        var received = new CopyOnWriteArrayList<SignalMessage>();

        traced.subscribeSignals(service, message -> {
            var current = otel.tracer().currentSpan();
            seenTraceId.set(current != null ? current.context().traceId() : null);
            seenHolder.set(TraceContextHolder.current());
            received.add(message);
        });
        Thread.sleep(500);

        var span = otel.tracer().nextSpan().name("publish").start();
        String publishedTraceId;
        try (var ignored = otel.tracer().withSpan(span)) {
            publishedTraceId = span.context().traceId();
            traced.publishSignal(service, new SignalMessage(
                    "order-1", "approval", objectMapper.valueToTree("granted")));
        } finally {
            span.end();
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> !received.isEmpty());

        assertEquals(publishedTraceId, seenTraceId.get(),
                "the handler must run inside the publisher's trace");
        assertNotNull(seenHolder.get(), "TraceContextHolder must carry the raw traceparent");
        assertTrue(TRACEPARENT_GRAMMAR.matcher(seenHolder.get()).matches());
        assertEquals(publishedTraceId, seenHolder.get().substring(3, 35),
                "the holder's traceparent must name the publisher's trace");
        assertNull(TraceContextHolder.current(),
                "the holder must not leak onto the test thread");
    }

    @Test
    @DisplayName("the holder is cleared after the handler returns, so the next record on the "
            + "same listener thread never inherits it")
    void holderIsClearedAfterTheHandler() throws Exception {
        var service = "svc-clear-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        var holderValues = new CopyOnWriteArrayList<String>();
        traced.subscribeSignals(service, message ->
                holderValues.add(String.valueOf(TraceContextHolder.current())));
        Thread.sleep(500);

        var span = otel.tracer().nextSpan().name("publish").start();
        try (var ignored = otel.tracer().withSpan(span)) {
            traced.publishSignal(service, new SignalMessage("order-1", "traced", null));
        } finally {
            span.end();
        }
        // Second record published with no span at all — same listener thread.
        traced.publishSignal(service, new SignalMessage("order-1", "untraced", null));

        await().atMost(Duration.ofSeconds(20)).until(() -> holderValues.size() == 2);
        assertTrue(TRACEPARENT_GRAMMAR.matcher(holderValues.get(0)).matches(),
                () -> "first record should carry a traceparent, was " + holderValues.get(0));
        assertEquals("null", holderValues.get(1),
                "the second, untraced record must not inherit the first record's context");
    }

    @Test
    @DisplayName("a record with no traceparent is delivered normally — absence degrades, never errors")
    void untracedRecordIsDeliveredNormally() throws Exception {
        var service = "svc-degrade-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        var received = new CopyOnWriteArrayList<SignalMessage>();
        var holder = new AtomicReference<>("sentinel");
        traced.subscribeSignals(service, message -> {
            holder.set(TraceContextHolder.current());
            received.add(message);
        });
        Thread.sleep(500);

        untraced.publishSignal(service, new SignalMessage("order-1", "approval", null));

        await().atMost(Duration.ofSeconds(20)).until(() -> !received.isEmpty());
        assertEquals("approval", received.getFirst().signalName());
        assertNull(holder.get());
    }

    @Test
    @DisplayName("task subscriptions restore the remote context too")
    void consumedTaskRestoresRemoteContext() throws Exception {
        var queue = "q-rt-" + testSuffix;
        var topic = "maestro.tasks." + queue;
        createTopics(topic);

        var seenTraceId = new AtomicReference<String>();
        var received = new CopyOnWriteArrayList<TaskMessage>();
        traced.subscribe(queue, message -> {
            var current = otel.tracer().currentSpan();
            seenTraceId.set(current != null ? current.context().traceId() : null);
            received.add(message);
        });
        Thread.sleep(500);

        var span = otel.tracer().nextSpan().name("publish").start();
        String publishedTraceId;
        try (var ignored = otel.tracer().withSpan(span)) {
            publishedTraceId = span.context().traceId();
            traced.publishTask(queue, new TaskMessage(
                    UUID.randomUUID(), "order-1", "OrderWorkflow", UUID.randomUUID(), "svc", null));
        } finally {
            span.end();
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> !received.isEmpty());
        assertEquals(publishedTraceId, seenTraceId.get());
    }

    // ── Hostile / malformed inbound headers (fix round 1, F1) ─────────

    /**
     * The extracted value does not stay in memory: it is persisted verbatim on
     * the signal row's {@code VARCHAR(128)} {@code trace_context} column. An
     * over-long header would make {@code saveSignal} fail, {@code deliverSignal}
     * would propagate that failure, the record would never be acked, redelivery
     * would exhaust its budget and the signal would be <b>dead-lettered</b> —
     * discarded, breaking the engine's "never discard a signal" invariant
     * because of decorative metadata.
     */
    @Test
    @DisplayName("an over-long traceparent is treated as absent — the signal is still delivered, "
            + "and nothing over-long can reach the signal row")
    void oversizedTraceparentIsTreatedAsAbsent() throws Exception {
        var service = "svc-oversize-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        var received = new CopyOnWriteArrayList<SignalMessage>();
        var holder = new AtomicReference<>("sentinel");
        traced.subscribeSignals(service, message -> {
            holder.set(TraceContextHolder.current());
            received.add(message);
        });
        Thread.sleep(500);

        // 512 characters — four times the column width.
        var oversized = "00-" + "a".repeat(509);
        publishRaw(topic, "order-1", new SignalMessage("order-1", "approval", null), oversized);

        await().atMost(Duration.ofSeconds(20)).until(() -> !received.isEmpty());
        assertEquals("approval", received.getFirst().signalName(),
                "the signal must be delivered, not rejected");
        assertNull(holder.get(),
                "an over-long traceparent must never reach the holder, and so can never reach "
                        + "the trace_context column");
    }

    @Test
    @DisplayName("a malformed or wrong-version traceparent is treated as absent, and the signal "
            + "is still delivered")
    void malformedTraceparentIsTreatedAsAbsent() throws Exception {
        var service = "svc-malformed-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        var seen = new CopyOnWriteArrayList<String>();
        traced.subscribeSignals(service, message ->
                seen.add(String.valueOf(TraceContextHolder.current())));
        Thread.sleep(500);

        for (var bogus : List.of(
                "not-a-traceparent",
                "",
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331",          // truncated
                "00-0AF7651916CD43DD8448EB211C80319C-B7AD6B7169203331-01",       // upper case
                "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")) {    // version != 00
            publishRaw(topic, "order-1", new SignalMessage("order-1", "approval", null), bogus);
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> seen.size() == 5);
        assertTrue(seen.stream().allMatch("null"::equals),
                () -> "every malformed traceparent must degrade to absent; holder values were " + seen);
    }

    @Test
    @DisplayName("extractTraceparent rejects a malformed header rather than returning it")
    void extractTraceparentRejectsMalformed() throws Exception {
        var service = "svc-extract-bad-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        publishRaw(topic, "order-1", new SignalMessage("order-1", "approval", null),
                "00-" + "a".repeat(509));

        var record = readOne(topic);
        assertNotNull(header(record, TRACEPARENT), "the bogus header really is on the wire");
        assertNull(propagation.extractTraceparent(record.headers()),
                "extraction is the choke point — nothing invalid may travel further");
    }

    // ── extractTraceparent (the value the signal row stores) ──────────

    @Test
    @DisplayName("extractTraceparent returns the raw header value, or null when absent")
    void extractTraceparentReturnsRawValueOrNull() throws Exception {
        var service = "svc-raw-" + testSuffix;
        var topic = "maestro.signals." + service;
        createTopics(topic);

        var span = otel.tracer().nextSpan().name("publish").start();
        try (var ignored = otel.tracer().withSpan(span)) {
            traced.publishSignal(service, new SignalMessage("order-1", "approval", null));
        } finally {
            span.end();
        }
        var tracedRecord = readOne(topic);
        assertEquals(header(tracedRecord, TRACEPARENT),
                propagation.extractTraceparent(tracedRecord.headers()));

        var plainService = "svc-raw-plain-" + testSuffix;
        var plainTopic = "maestro.signals." + plainService;
        createTopics(plainTopic);
        untraced.publishSignal(plainService, new SignalMessage("order-1", "approval", null));
        assertNull(propagation.extractTraceparent(readOne(plainTopic).headers()));
    }

    @Test
    @DisplayName("the propagator's declared fields include the pinned header names")
    void propagatorFieldsIncludeTheContractHeaders() {
        assertTrue(otel.propagator().fields().contains(TRACEPARENT),
                () -> "propagator fields were " + otel.propagator().fields());
        assertFalse(otel.propagator().fields().isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Publishes with an arbitrary, deliberately unvalidated traceparent header. */
    private void publishRaw(String topic, String key, Object message, String traceparent)
            throws Exception {
        var record = new ProducerRecord<String, byte[]>(
                topic, key, objectMapper.writeValueAsBytes(message));
        record.headers().add(TRACEPARENT, traceparent.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();
    }

    private ConsumerRecord<String, byte[]> readOne(String topic) {
        try (var consumer = consumerFactory.createConsumer("raw-" + UUID.randomUUID(), null)) {
            var partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var deadline = Instant.now().plusSeconds(20);
            while (Instant.now().isBefore(deadline)) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records.records(partition)) {
                    return record;
                }
            }
            throw new AssertionError("no record arrived on topic " + topic);
        }
    }

    private static @Nullable String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Set<String> traceHeaderNames(ConsumerRecord<String, byte[]> record) {
        var names = new java.util.HashSet<String>();
        record.headers().forEach(h -> {
            if (ALLOWED_TRACE_HEADERS.contains(h.key()) || h.key().startsWith("trace")
                    || h.key().equals(BAGGAGE)) {
                names.add(h.key());
            }
        });
        return names;
    }
}
