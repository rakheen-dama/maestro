package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.SignalInfo;
import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.messaging.kafka.MaestroSignalHeaders;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * At-least-once delivery, end to end, against signal identity (V9): a
 * redelivered or republished signal is <b>one</b> signal row, while two
 * distinct signals — even with identical payloads — stay two.
 *
 * <p>Before V9 this suite pinned the opposite: every delivery became a row and
 * the surplus "simply remained unconsumed". That was harmless for a single
 * await and wrong for any workflow that awaits the same name again (a review
 * loop, {@code collectSignals(name, n)}): the twin answered the next await.
 * Case 3 keeps the old shape as the negative control — a producer that names
 * nothing and genuinely publishes twice still gets two signals.
 */
@SpringBootTest(
        classes = {
                KafkaSignalTestApplication.class,
                KafkaDuplicateDeliveryIT.RouterConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.service-name=" + KafkaDuplicateDeliveryIT.SERVICE,
                "maestro.lock.type=postgres",
                "maestro.messaging.type=kafka",
                "maestro.messaging.topics.admin-events=" + KafkaDuplicateDeliveryIT.ADMIN_TOPIC,
                "maestro.recovery.enabled=false",
                "maestro.messaging.redelivery.initial-interval=200ms"
        })
@Tag("integration")
@DisplayName("Duplicated Kafka signals are suppressed by message identity")
class KafkaDuplicateDeliveryIT extends KafkaSpringIntegrationSupport {

    static final String SERVICE = "kafka-duplicate";

    static final String EVENT_TOPIC = "it.duplicate.approvals";

    static final String ADMIN_TOPIC = "it.duplicate.admin";

    private static final Duration BOUND = Duration.ofSeconds(30);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MaestroClient maestro;

    @Autowired
    private FailAfterSaveStore failAfterSave;

    @Autowired
    private DuplicateRecorder duplicates;

    /**
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    @BeforeAll
    static void createDuplicateTopics() throws ExecutionException, InterruptedException {
        // .DLT companions included — required by the startup topic verification.
        createTopics(EVENT_TOPIC, EVENT_TOPIC + ".DLT", ADMIN_TOPIC,
                "maestro.signals." + SERVICE, "maestro.signals." + SERVICE + ".DLT");
    }

    @Test
    @DisplayName("1. the same record redelivered after its row committed is one row, one consume, one completion")
    void sameRecordRedelivered_isOneSignal() throws Exception {
        var workflowId = "dup-redeliver-" + UUID.randomUUID().toString().substring(0, 8);
        start(KafkaTestWorkflows.ApprovalWorkflow.class, workflowId);

        // The insert commits, then the listener fails before the offset commit:
        // exactly the crash/rebalance window. The record is redelivered.
        failAfterSave.armFor(workflowId);
        publish(EVENT_TOPIC, workflowId, new ApprovalEvent(workflowId, "approved"));

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:approved", serializer.deserialize(completed.output(), String.class));
        assertTrue(failAfterSave.fired(workflowId), "the injected post-commit failure must have fired");

        var rows = rows(workflowId);
        assertEquals(1, rows.size(), "the redelivery must not become a second row: " + rows);
        assertTrue(rows.getFirst().consumed());
        assertTrue(rows.getFirst().messageId() != null && rows.getFirst().messageId().startsWith("kafka:"),
                "a header-less domain record is identified by its topic-id coordinate: " + rows);
        assertEquals(1, signalReceived(completed.id()));
        assertEquals(1, lifecycleEvents(workflowId, LifecycleEventType.WORKFLOW_COMPLETED).size());
        assertEquals(1, duplicates.count(workflowId), "the redelivery is reported as one suppressed duplicate");
    }

    @Test
    @DisplayName("2. a producer republish carrying the same maestro-message-id is one row")
    void republishWithHeader_isOneSignal() throws Exception {
        var workflowId = "dup-republish-" + UUID.randomUUID().toString().substring(0, 8);
        start(KafkaTestWorkflows.ApprovalWorkflow.class, workflowId);

        var event = new ApprovalEvent(workflowId, "approved");
        publishWithId(workflowId, event, "evt-1");
        publishWithId(workflowId, event, "evt-1");

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        await().atMost(BOUND).until(() -> duplicates.count(workflowId) == 1);
        var rows = rows(workflowId);
        assertEquals(1, rows.size(), "two records, one identity, one row: " + rows);
        assertEquals("hdr:" + EVENT_TOPIC + ":evt-1", rows.getFirst().messageId());
        assertEquals(1, signalReceived(completed.id()));
    }

    @Test
    @DisplayName("3. negative control: a republish with no id at all is two signals — one consumed, one surplus")
    void republishWithoutId_isTwoSignals() throws Exception {
        var workflowId = "dup-noid-" + UUID.randomUUID().toString().substring(0, 8);
        start(KafkaTestWorkflows.ApprovalWorkflow.class, workflowId);

        var event = new ApprovalEvent(workflowId, "approved");
        publish(EVENT_TOPIC, workflowId, event);
        publish(EVENT_TOPIC, workflowId, event);

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        await().atMost(BOUND).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertEquals(2, rows(workflowId).size(),
                        "distinct records with no producer id are distinct signals (distinct coordinates)"));
        assertEquals(1, rows(workflowId).stream().filter(Row::consumed).count());
        assertEquals(1, signalReceived(completed.id()));
        assertEquals(0, duplicates.count(workflowId));
    }

    @Test
    @DisplayName("4. duplicates with one id that arrive before the workflow exists are one adopted row")
    void orphanDuplicatesWithId_areOneAdoptedRow() throws Exception {
        var workflowId = "dup-orphan-" + UUID.randomUUID().toString().substring(0, 8);

        var event = new ApprovalEvent(workflowId, "early");
        publishWithId(workflowId, event, "early-1");
        publishWithId(workflowId, event, "early-1");
        await().atMost(BOUND).until(() -> duplicates.count(workflowId) == 1);
        assertEquals(1, rows(workflowId).size(), "one orphan row for one identity");

        // Started with the signal already pending: it never parks.
        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:early", serializer.deserialize(completed.output(), String.class));

        var rows = rows(workflowId);
        assertEquals(1, rows.size());
        assertTrue(rows.getFirst().consumed());
        assertEquals(completed.id(), rows.getFirst().workflowInstanceId(), "the one row was adopted");
    }

    @Test
    @DisplayName("5. a quorum of two identical payloads with distinct ids collects both")
    void quorumWithIdenticalPayloadsAndDistinctIds_collectsBoth() throws Exception {
        var workflowId = "dup-quorum-" + UUID.randomUUID().toString().substring(0, 8);
        start(KafkaTestWorkflows.QuorumWorkflow.class, workflowId);

        var vote = new ApprovalEvent(workflowId, "yes");
        publishWithId(workflowId, vote, "voter-a");
        publishWithId(workflowId, vote, "voter-b");

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:yes,yes", serializer.deserialize(completed.output(), String.class),
                "identity is the id, not the payload: two voters are two votes");
        var rows = rows(workflowId);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(Row::consumed));
        assertEquals(2, signalReceived(completed.id()));
        assertEquals(0, duplicates.count(workflowId));
    }

    @Test
    @DisplayName("6. WorkflowHandle.signal(name, payload, id) retried with the same id is one row")
    void handleSignalRetriedWithSameId_isOneSignal() throws Exception {
        var workflowId = "dup-handle-" + UUID.randomUUID().toString().substring(0, 8);
        start(KafkaTestWorkflows.ApprovalWorkflow.class, workflowId);

        var handle = maestro.getWorkflow(workflowId);
        assertTrue(handle.signal(KafkaTestWorkflows.APPROVAL_SIGNAL, "approved", "req-1"));
        assertEquals(false, handle.signal(KafkaTestWorkflows.APPROVAL_SIGNAL, "approved", "req-1"),
                "the client retry is reported as a duplicate");

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        var rows = rows(workflowId);
        assertEquals(1, rows.size());
        assertEquals("client:req-1", rows.getFirst().messageId());
        assertEquals(1, signalReceived(completed.id()));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void start(Class<?> workflowClass, String workflowId) {
        maestro.newWorkflow(workflowClass, WorkflowOptions.builder().workflowId(workflowId).build())
                .startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);
    }

    private static void publishWithId(String key, Object event, String messageId) {
        try {
            var record = new ProducerRecord<String, byte[]>(EVENT_TOPIC, key, MAPPER.writeValueAsBytes(event));
            MaestroSignalHeaders.setMessageId(record.headers(), messageId);
            producer().send(record).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to publish", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing", e);
        }
    }

    private long signalReceived(UUID instanceId) {
        return store.getEvents(instanceId).stream()
                .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED)
                .count();
    }

    /** A signal row with its identity. */
    private record Row(@Nullable UUID workflowInstanceId, boolean consumed, @Nullable String messageId) {}

    private List<Row> rows(String workflowId) throws SQLException {
        var sql = "SELECT workflow_instance_id, consumed, message_id FROM maestro_workflow_signal"
                + " WHERE workflow_id = ? ORDER BY received_at, id";
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                var rows = new ArrayList<Row>();
                while (rs.next()) {
                    rows.add(new Row((UUID) rs.getObject(1), rs.getBoolean(2), rs.getString(3)));
                }
                return rows;
            }
        }
    }

    /**
     * Reads the admin topic from the beginning and keeps one workflow's events
     * of one type.
     */
    private List<WorkflowLifecycleEvent> lifecycleEvents(String workflowId, LifecycleEventType type) {
        try (var recorder = recorderFor(ADMIN_TOPIC)) {
            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertTrue(recorder.messages(WorkflowLifecycleEvent.class).stream()
                                    .anyMatch(e -> e.workflowId().equals(workflowId) && e.eventType() == type),
                            "no " + type + " event for " + workflowId + " on " + ADMIN_TOPIC));
            return recorder.messages(WorkflowLifecycleEvent.class).stream()
                    .filter(e -> e.workflowId().equals(workflowId) && e.eventType() == type)
                    .toList();
        }
    }

    // ── test-local fixtures ─────────────────────────────────────────────

    /** Registers this suite's router, the store fault injector and the duplicate recorder. */
    @TestConfiguration
    public static class RouterConfiguration {

        /** @return the router under test */
        @Bean
        DuplicateRouter duplicateRouter() {
            return new DuplicateRouter();
        }

        /** @return the post-commit fault switch the store wrapper consults */
        @Bean
        FailAfterSaveStore failAfterSaveStore() {
            return new FailAfterSaveStore();
        }

        /** @return records every suppressed duplicate, by workflow */
        @Bean
        DuplicateRecorder duplicateRecorder() {
            return new DuplicateRecorder();
        }

        /**
         * Wraps the auto-configured {@link WorkflowStore} so a
         * {@code saveSignalIfAbsent} can be made to throw <em>after</em> its
         * row committed.
         *
         * @param faults the fault switch
         * @return the wrapping post-processor
         */
        @Bean
        static BeanPostProcessor failAfterSaveWrapper(
                org.springframework.beans.factory.ObjectProvider<FailAfterSaveStore> faults) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof WorkflowStore delegate)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                            new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                                Object result;
                                try {
                                    result = method.invoke(delegate, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if (method.getName().equals("saveSignalIfAbsent")
                                        && Boolean.TRUE.equals(result)) {
                                    faults.getObject().afterSave((WorkflowSignal) args[0]);
                                }
                                return result;
                            });
                }
            };
        }
    }

    /** One-shot fault: throws once after a committed save for an armed workflow. */
    public static final class FailAfterSaveStore {
        private final ConcurrentHashMap<String, Boolean> armed = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Boolean> fired = new ConcurrentHashMap<>();

        void armFor(String workflowId) {
            armed.put(workflowId, Boolean.TRUE);
        }

        boolean fired(String workflowId) {
            return fired.containsKey(workflowId);
        }

        void afterSave(WorkflowSignal signal) {
            if (armed.remove(signal.workflowId()) != null) {
                fired.put(signal.workflowId(), Boolean.TRUE);
                throw new IllegalStateException("injected: listener dies after the signal row committed");
            }
        }
    }

    /** Collects {@code signalDuplicateSuppressed} callbacks. */
    public static final class DuplicateRecorder implements EngineObserver {
        private final List<SignalInfo> seen = new CopyOnWriteArrayList<>();

        @Override
        public void signalDuplicateSuppressed(SignalInfo s) {
            seen.add(s);
        }

        long count(String workflowId) {
            return seen.stream().filter(s -> s.workflowId().equals(workflowId)).count();
        }
    }

    /**
     * Routes every delivery — including a redelivery — to the same workflow and
     * signal, exactly as a production router would.
     *
     * <h2>Thread Safety</h2>
     * <p>Stateless; invoked on the listener container's consumer thread.
     */
    public static class DuplicateRouter {

        /**
         * @param event the domain event
         * @return the signal routing for that event
         */
        @MaestroSignalListener(topic = EVENT_TOPIC, signalName = KafkaTestWorkflows.APPROVAL_SIGNAL)
        public SignalRouting route(ApprovalEvent event) {
            return SignalRouting.builder()
                    .workflowId(event.workflowId())
                    .payload(event.decision())
                    .build();
        }
    }
}
