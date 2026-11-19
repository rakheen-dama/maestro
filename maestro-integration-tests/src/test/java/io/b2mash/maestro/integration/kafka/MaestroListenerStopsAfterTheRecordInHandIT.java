package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1 review M1 (Issue 69): a stopping Maestro listener
 * container finishes the record in hand and invokes the listener for nothing
 * else from its last poll ({@code stopImmediate}). The records it had polled
 * but not handled stay uncommitted, and the next node to own the partition
 * handles each of them exactly once: nothing lost, nothing handled twice.
 *
 * <p>One partition, a backlog of {@link #BACKLOG} events — all in the first
 * node's first poll ({@code max.poll.records} 500). The first node's listener
 * holds the {@link #HOLD_AT}th event until its engine is shutting down, so the
 * stop lands while that record is in hand and the rest of the batch is still
 * to come.
 */
@Tag("integration")
@DisplayName("A stopping Maestro listener container stops after the record in hand; the rest goes to the next node")
class MaestroListenerStopsAfterTheRecordInHandIT extends KafkaSpringIntegrationSupport {

    static final String SERVICE = "kafka-stop-immediate";
    static final String EVENT_TOPIC = "it.stopimmediate.approvals";
    static final String ADMIN_TOPIC = "it.stopimmediate.admin";
    static final int BACKLOG = 40;
    static final int HOLD_AT = 3;

    /** Which node handled each event, in order. */
    static final Map<String, List<String>> HANDLED = new ConcurrentHashMap<>();
    static final List<String> FIRST_NODE = new CopyOnWriteArrayList<>();
    static final AtomicInteger FIRST_NODE_CALLS = new AtomicInteger();
    static volatile @Nullable WorkflowExecutor firstNode;
    static volatile String node = "first";

    @BeforeAll
    static void createStopTopics() throws ExecutionException, InterruptedException {
        createTopics(EVENT_TOPIC, EVENT_TOPIC + ".DLT", ADMIN_TOPIC,
                "maestro.signals." + SERVICE, "maestro.signals." + SERVICE + ".DLT");
    }

    @Test
    @DisplayName("the listener handles nothing after the record in hand, and the next node handles the rest exactly once")
    void theListenerStopsAfterTheRecordInHandAndTheNextNodeHandlesTheRestOnce() {
        // Published before the group exists: its first poll (offset reset earliest)
        // takes the whole backlog in one batch.
        IntStream.range(0, BACKLOG).forEach(i ->
                publish(EVENT_TOPIC, "k", new ApprovalEvent("wf-stop-" + i, "APPROVED")));
        var first = start();
        firstNode = first.getBean(WorkflowExecutor.class);
        await().atMost(Duration.ofSeconds(60)).until(() -> FIRST_NODE_CALLS.get() >= HOLD_AT);

        first.close(); // shutdown begins while the HOLD_AT-th record is in hand

        assertEquals(HOLD_AT, FIRST_NODE_CALLS.get(),
                "the stopping container invoked the listener for nothing after the record in hand: " + FIRST_NODE);

        node = "second";
        var second = start();
        try {
            await().atMost(Duration.ofSeconds(60)).until(() -> HANDLED.size() == BACKLOG);
            // A little longer, to see a duplicate if one were coming.
            await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> true);
            HANDLED.forEach((id, nodes) -> assertEquals(1, nodes.size(), id + " handled " + nodes));
            assertTrue(IntStream.range(0, HOLD_AT).allMatch(i -> HANDLED.get("wf-stop-" + i).equals(List.of("first"))),
                    "the first node's records, the one in hand included, were committed: " + HANDLED);
        } finally {
            second.close();
        }
    }

    private static ConfigurableApplicationContext start() {
        var dataSource = newDataSource();
        return new SpringApplicationBuilder(KafkaSignalTestApplication.class, RouterConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + dataSource.getUrl(),
                        "spring.datasource.username=" + dataSource.getUser(),
                        "spring.datasource.password=" + dataSource.getPassword(),
                        "spring.kafka.bootstrap-servers=" + bootstrapServers(),
                        "maestro.service-name=" + SERVICE,
                        "maestro.lock.type=postgres",
                        "maestro.messaging.type=kafka",
                        "maestro.messaging.topics.admin-events=" + ADMIN_TOPIC,
                        "maestro.recovery.enabled=false",
                        "maestro.shutdown.timeout=20s")
                .run();
    }

    /** The slow router. */
    @TestConfiguration
    public static class RouterConfiguration {

        /** @return the router */
        @Bean
        HoldingRouter holdingRouter() {
            return new HoldingRouter();
        }
    }

    /** Records which node handled each event; on the first node, holds the HOLD_AT-th until shutdown. */
    public static class HoldingRouter {

        /**
         * @param event the domain event
         * @return where the signal goes
         */
        @MaestroSignalListener(topic = EVENT_TOPIC, signalName = KafkaTestWorkflows.APPROVAL_SIGNAL)
        public SignalRouting route(ApprovalEvent event) throws InterruptedException {
            var who = node;
            HANDLED.computeIfAbsent(event.workflowId(), id -> new CopyOnWriteArrayList<>()).add(who);
            if (who.equals("first")) {
                FIRST_NODE.add(event.workflowId());
                if (FIRST_NODE_CALLS.incrementAndGet() == HOLD_AT) {
                    var engine = firstNode;
                    while (engine == null || !engine.isShuttingDown()) {
                        Thread.sleep(20);
                        engine = firstNode;
                    }
                    Thread.sleep(500); // past the engine's stop request to this container
                }
            }
            return SignalRouting.builder().workflowId(event.workflowId()).payload(event.decision()).build();
        }
    }
}
