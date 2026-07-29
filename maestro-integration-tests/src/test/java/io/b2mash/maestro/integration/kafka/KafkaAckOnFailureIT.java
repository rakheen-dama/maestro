package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The at-least-once contract for signal ingestion: <b>a handler that fails must
 * not cost the signal.</b>
 *
 * <p>Both delivery paths are covered, because both used to lose signals:
 *
 * <ul>
 *   <li><b>{@code @MaestroSignalListener} path</b> — the bean post-processor
 *       rethrows, so the record is redelivered and a <em>transient</em> failure
 *       recovers, which
 *       {@link #transientHandlerFailure_isRedeliveredUntilItSucceeds()} pins.
 *       A <em>persistent</em> failure used to exhaust the retries and then be
 *       logged and skipped — offset committed, signal gone, no dead-letter
 *       topic. It must now be parked on {@code <topic>.DLT}.</li>
 *   <li><b>Engine signal channel</b> — {@code KafkaWorkflowMessaging}
 *       {@code subscribeSignals} used to wrap the handler in {@code try/catch}
 *       and only log, so the record was acked after a <em>single</em> attempt,
 *       silently defeating {@code SignalSubscriptionRunner}, which deliberately
 *       rethrows "so the transport does NOT ack". It must now redeliver until
 *       the signal is durable, and park the record that never will be.</li>
 * </ul>
 *
 * <p>Dead-letter topics are ordinary topics: Maestro creates none, so this
 * suite pre-creates them in {@code @BeforeAll} exactly as an operator would
 * declare them.
 */
@SpringBootTest(
        classes = {
                KafkaSignalTestApplication.class,
                KafkaAckOnFailureIT.RouterConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.service-name=" + KafkaAckOnFailureIT.SERVICE,
                "maestro.lock.type=postgres",
                "maestro.messaging.type=kafka",
                "maestro.messaging.topics.admin-events=" + KafkaAckOnFailureIT.ADMIN_TOPIC,
                "maestro.recovery.enabled=false",
                // A tight budget so exhaustion (~1.75s of backoff) fits this
                // suite's bound; the production default is 10 attempts over
                // ~2.5 minutes. The transient test fails twice, still under 4.
                "maestro.messaging.redelivery.max-attempts=4",
                "maestro.messaging.redelivery.initial-interval=250ms"
        })
@Tag("integration")
@DisplayName("A failing signal handler must not lose the signal")
class KafkaAckOnFailureIT extends KafkaSpringIntegrationSupport {

    static final String SERVICE = "kafka-ack";

    static final String EVENT_TOPIC = "it.ack.approvals";

    /** Where a record must land once the listener's retries are exhausted. */
    static final String DLT_TOPIC = EVENT_TOPIC + ".DLT";

    /** The engine channel used by the adapter-level test. */
    static final String CHANNEL_TOPIC = "it.ack.channel";

    /** The engine channel used by the adapter-level poison test. */
    static final String POISON_CHANNEL_TOPIC = "it.ack.channel.poison";

    /** Where the engine channel parks a record it can never process. */
    static final String POISON_CHANNEL_DLT = POISON_CHANNEL_TOPIC + ".DLT";

    static final String ADMIN_TOPIC = "it.ack.admin";

    /** Payload that makes the router fail on every attempt. */
    static final String POISON = "poison";

    private static final Duration BOUND = Duration.ofSeconds(30);

    @Autowired
    private MaestroClient maestro;

    @Autowired
    private FailingRouter router;

    /**
     * @throws ExecutionException   if topic creation fails
     * @throws InterruptedException if interrupted while waiting
     */
    @BeforeAll
    static void createAckTopics() throws ExecutionException, InterruptedException {
        createTopics(EVENT_TOPIC, DLT_TOPIC, CHANNEL_TOPIC, POISON_CHANNEL_TOPIC, POISON_CHANNEL_DLT,
                ADMIN_TOPIC, "maestro.signals." + SERVICE);
    }

    @BeforeEach
    void resetRouter() {
        router.reset();
    }

    @Test
    @DisplayName("a transient handler failure is redelivered until it succeeds, and the workflow completes")
    void transientHandlerFailure_isRedeliveredUntilItSucceeds() throws Exception {
        var workflowId = "ack-transient-" + UUID.randomUUID().toString().substring(0, 8);
        router.failNext(2);

        maestro.newWorkflow(KafkaTestWorkflows.ApprovalWorkflow.class,
                WorkflowOptions.builder().workflowId(workflowId).build()).startAsync("seed");
        awaitStatus(workflowId, WorkflowStatus.WAITING_SIGNAL, BOUND);

        publish(EVENT_TOPIC, workflowId, new ApprovalEvent(workflowId, "approved"));

        var completed = awaitStatus(workflowId, WorkflowStatus.COMPLETED, BOUND);
        assertEquals("seed:approved", serializer.deserialize(completed.output(), String.class));

        assertTrue(router.attempts() >= 3,
                "the record must be redelivered after each failure, but was attempted "
                        + router.attempts() + " time(s)");

        // The failed attempts never reached deliverSignal, so they left no rows:
        // redelivery must not multiply the signal either.
        assertEquals(1, signalRows(workflowId).size(),
                "only the successful attempt may persist a signal row");
    }

    @Test
    @DisplayName("the engine signal channel redelivers when the handler throws")
    void signalChannelHandlerFailure_isRedelivered() throws Exception {
        // Two failures then success — comfortably inside the budget, so
        // this channel never reaches its dead-letter topic.
        var config = channelConfig(CHANNEL_TOPIC, 5);
        var messaging = new KafkaWorkflowMessaging(
                producer(), consumerFactory(config.consumerGroup()), objectMapper, config);

        var attempts = new AtomicInteger();
        var delivered = new AtomicInteger();
        try {
            messaging.subscribeSignals(SERVICE, message -> {
                // Two failures then success: exactly the transient-fault shape
                // SignalSubscriptionRunner rethrows for.
                if (attempts.incrementAndGet() <= 2) {
                    throw new IllegalStateException("store unavailable — signal is not durable yet");
                }
                delivered.incrementAndGet();
            });

            messaging.publishSignal(SERVICE, new SignalMessage(
                    "ack-channel-wf", KafkaTestWorkflows.APPROVAL_SIGNAL, objectMapper.valueToTree("granted")));

            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertEquals(1, delivered.get(),
                            "a signal whose handler failed must be redelivered until it is durable; "
                                    + "attempts=" + attempts.get()));
            assertTrue(attempts.get() >= 3, "expected redelivery, saw " + attempts.get() + " attempt(s)");
        } finally {
            messaging.destroy();
        }
    }

    @Test
    @DisplayName("a persistently failing @MaestroSignalListener record is dead-lettered, not dropped")
    void persistentHandlerFailure_isDeadLetteredNotDropped() {
        var workflowId = "ack-poison-" + UUID.randomUUID().toString().substring(0, 8);
        var event = new ApprovalEvent(workflowId, POISON);

        try (var dlt = recorderFor(DLT_TOPIC)) {
            publish(EVENT_TOPIC, workflowId, event);

            // Retries are expected — and expected to be bounded, so this must
            // terminate rather than loop on the poison record forever.
            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertEquals(1, dlt.messages(ApprovalEvent.class).size(),
                            "an unprocessable record must be dead-lettered, not silently skipped; "
                                    + "listener attempts=" + router.attempts()));
            assertEquals(event, dlt.messages(ApprovalEvent.class).getFirst());
            assertEquals(workflowId, dlt.keys().getFirst(), "the DLT record must keep its partition key");
        }
    }

    @Test
    @DisplayName("the engine signal channel dead-letters a record it can never process")
    void signalChannelPersistentFailure_isDeadLetteredNotDropped() {
        var config = channelConfig(POISON_CHANNEL_TOPIC, CHANNEL_MAX_ATTEMPTS);
        var messaging = new KafkaWorkflowMessaging(
                producer(), consumerFactory(config.consumerGroup()), objectMapper, config);

        var attempts = new AtomicInteger();
        var signal = new SignalMessage(
                "ack-channel-poison-wf", KafkaTestWorkflows.APPROVAL_SIGNAL,
                objectMapper.valueToTree("granted"));

        try (var dlt = recorderFor(POISON_CHANNEL_DLT)) {
            messaging.subscribeSignals(SERVICE, message -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("poison signal — this handler can never process it");
            });

            messaging.publishSignal(SERVICE, signal);

            await().atMost(BOUND).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                    assertEquals(1, dlt.messages(SignalMessage.class).size(),
                            "an unprocessable signal must be parked, not skipped; attempts="
                                    + attempts.get()));
            assertEquals(signal.workflowId(), dlt.messages(SignalMessage.class).getFirst().workflowId());
            assertEquals(signal.signalName(), dlt.messages(SignalMessage.class).getFirst().signalName());
            assertEquals(signal.workflowId(), dlt.keys().getFirst(),
                    "the parked record must keep its partition key");

            // Bounded: the budget is spent once and the channel moves on.
            assertEquals(CHANNEL_MAX_ATTEMPTS, attempts.get(),
                    "redelivery must be bounded by the configured budget");
        } finally {
            messaging.destroy();
        }
    }

    // ── test-local fixtures ─────────────────────────────────────────────

    /** Attempt budget for the engine channel's poison test. */
    private static final int CHANNEL_MAX_ATTEMPTS = 3;

    /**
     * Builds an adapter config for a directly constructed engine channel, with
     * a budget small enough to exhaust inside {@link #BOUND}.
     *
     * @param signalsTopic the fixed signal topic this channel consumes
     * @param maxAttempts  total delivery attempts, including the first
     * @return the config
     */
    private static KafkaMessagingConfig channelConfig(String signalsTopic, int maxAttempts) {
        return new KafkaMessagingConfig(
                null, signalsTopic, ADMIN_TOPIC, "ack-channel-" + UUID.randomUUID(),
                maxAttempts, Duration.ofMillis(250), 2.0, Duration.ofSeconds(1), ".DLT");
    }

    /** Registers this suite's router; see {@code KafkaSignalListenerRoundTripIT}. */
    @TestConfiguration
    public static class RouterConfiguration {

        /** @return the router under test */
        @Bean
        FailingRouter failingRouter() {
            return new FailingRouter();
        }
    }

    /**
     * A router that can fail a bounded number of times, or forever for a poison
     * payload — the two failure shapes the ack contract has to distinguish.
     *
     * <h2>Thread Safety</h2>
     * <p>Counters are atomic; the router is invoked on the listener container's
     * consumer thread and read from the test thread.
     */
    public static class FailingRouter {

        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger failuresRemaining = new AtomicInteger();

        /**
         * @param event the domain event
         * @return the signal routing once this router is done failing
         */
        @MaestroSignalListener(topic = EVENT_TOPIC, signalName = KafkaTestWorkflows.APPROVAL_SIGNAL)
        public SignalRouting route(ApprovalEvent event) {
            attempts.incrementAndGet();
            if (POISON.equals(event.decision())) {
                throw new IllegalStateException("poison record — this router can never process it");
            }
            if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new IllegalStateException("transient routing failure");
            }
            return SignalRouting.builder()
                    .workflowId(event.workflowId())
                    .payload(event.decision())
                    .build();
        }

        /** @param count how many of the next invocations must fail */
        void failNext(int count) {
            failuresRemaining.set(count);
        }

        /** @return how many times a record has been handed to this router */
        int attempts() {
            return attempts.get();
        }

        /** Clears counters between tests — the bean is a context-wide singleton. */
        void reset() {
            attempts.set(0);
            failuresRemaining.set(0);
        }
    }
}
