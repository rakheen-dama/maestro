package io.b2mash.maestro.integration.kafka;

import io.b2mash.maestro.core.engine.LifecycleRegistry;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.GroupListing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pilot readiness P1, task 5 (Issue 69): Maestro's Kafka listener containers —
 * the engine's signal container and every {@code @MaestroSignalListener}
 * container — stop <em>inside</em> the engine's shutdown, at its start, and
 * are waited for on its one deadline. So no Maestro consumer is still in its
 * consumer group while the drain waits, partitions move to peers while this
 * node drains, and none outlives {@code shutdown()} into bean destruction.
 *
 * <p>The drain is held open by a registry member that leaves only when the
 * test lets it, so "while the drain is still waiting" is deterministic. The
 * consumer groups are read from the broker: a stopped container has left its
 * group.
 */
@SpringBootTest(
        classes = {
                KafkaSignalTestApplication.class,
                MaestroContainersStopInTheDrainIT.RouterConfiguration.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "maestro.service-name=" + MaestroContainersStopInTheDrainIT.SERVICE,
                "maestro.lock.type=postgres",
                "maestro.messaging.type=kafka",
                "maestro.messaging.topics.admin-events=" + MaestroContainersStopInTheDrainIT.ADMIN_TOPIC,
                "maestro.recovery.enabled=false",
                "maestro.shutdown.timeout=20s"
        })
@Tag("integration")
@DisplayName("Maestro's Kafka containers stop inside the engine's shutdown, while the drain still waits")
class MaestroContainersStopInTheDrainIT extends KafkaSpringIntegrationSupport {

    static final String SERVICE = "kafka-drain";
    static final String EVENT_TOPIC = "it.drain.approvals";
    static final String ADMIN_TOPIC = "it.drain.admin";
    private static final String GROUP_PREFIX = "maestro-" + SERVICE;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private WorkflowExecutor executor;

    @BeforeAll
    static void createDrainTopics() throws ExecutionException, InterruptedException {
        createTopics(EVENT_TOPIC, EVENT_TOPIC + ".DLT", ADMIN_TOPIC,
                "maestro.signals." + SERVICE, "maestro.signals." + SERVICE + ".DLT");
    }

    @Test
    @DisplayName("every Maestro container has left its consumer group while the drain is still waiting")
    void everyMaestroContainerHasStoppedWhileTheDrainIsStillWaiting() throws Exception {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
            // The engine's signal container and the listener's container have joined.
            await().atMost(Duration.ofSeconds(60)).until(() -> groupsWithMembers(admin).size() >= 2);
            var groups = groupsWithMembers(admin);

            var hold = executor.lifecycle().join(LifecycleRegistry.Kind.TASK, new LifecycleRegistry.Member() {
                @Override
                public String name() {
                    return "test-holds-the-drain";
                }

                @Override
                public void requestStop() {
                    // leaves only when the test lets it
                }
            }).orElseThrow();
            var closer = Thread.ofVirtual().start(context::close);
            await().atMost(Duration.ofSeconds(10)).until(executor::isShuttingDown);
            var t0 = System.nanoTime();

            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                    .until(() -> membersOf(admin, groups) == 0);
            var stoppedAfterMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            System.out.printf("[drain] every Maestro consumer left its group %d ms into the shutdown (groups %s)%n",
                    stoppedAfterMs, groups);
            assertTrue(closer.isAlive(), "the shutdown is still waiting for the held member");

            hold.leave();
            assertTrue(closer.join(Duration.ofSeconds(30)), "the context closed once the drain was released");
        }
    }

    private static List<String> groupsWithMembers(AdminClient admin) throws Exception {
        var ids = admin.listGroups().all().get(10, TimeUnit.SECONDS).stream()
                .map(GroupListing::groupId)
                .filter(id -> id.startsWith(GROUP_PREFIX))
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return admin.describeConsumerGroups(ids).all().get(10, TimeUnit.SECONDS).values().stream()
                .filter(d -> !d.members().isEmpty())
                .map(d -> d.groupId())
                .toList();
    }

    private static int membersOf(AdminClient admin, List<String> groups) throws Exception {
        return admin.describeConsumerGroups(groups).all().get(10, TimeUnit.SECONDS).values().stream()
                .mapToInt(d -> d.members().size())
                .sum();
    }

    /** One {@code @MaestroSignalListener}, so a listener container exists beside the engine's. */
    @TestConfiguration
    public static class RouterConfiguration {

        /** @return the router */
        @Bean
        DrainRouter drainRouter() {
            return new DrainRouter();
        }
    }

    /** Routes an approval event to a workflow signal. */
    public static class DrainRouter {

        /**
         * @param event the domain event
         * @return where the signal goes
         */
        @MaestroSignalListener(topic = EVENT_TOPIC, signalName = KafkaTestWorkflows.APPROVAL_SIGNAL)
        public SignalRouting route(ApprovalEvent event) {
            return SignalRouting.builder().workflowId(event.workflowId()).payload(event.decision()).build();
        }
    }
}
