package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import io.b2mash.maestro.spring.config.inheritedfixture.InheritedFixtures;
import io.b2mash.maestro.spring.config.inheritedfixture.InheritedGenericWorkflow;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Issue 94 through the starter: stubs built by
 * {@code ActivityStubBeanPostProcessor} for an activity interface that
 * inherits {@code T one()} and {@code List<T> all()} from
 * {@code Catalog<T>} hand workflow code {@code Order}s on the first run and
 * on the replay after the node stops and a second context adopts the run;
 * and the {@code @WorkflowMethod} inherited from {@code TypedWorkflow<I>}
 * receives its input as the {@code OrderRequest} the workflow class binds.
 */
@DisplayName("Inherited generic activity and workflow methods — the Spring path (Issue 94)")
class InheritedGenericSpringPathTest {

    private static final Duration BOUND = Duration.ofSeconds(10);

    private final InMemoryWorkflowStore store = new InMemoryWorkflowStore();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withBean(WorkflowStore.class, () -> store)
            .withBean(DistributedLock.class, InMemoryDistributedLock::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(InheritedFixtures.Orders.class, InheritedFixtures.OrdersImpl::new)
            .withPropertyValues(
                    "maestro.service-name=inherited-generic-test",
                    "maestro.testing.recovery.enabled=false",
                    "maestro.workflow-packages=io.b2mash.maestro.spring.config.inheritedfixture");

    @BeforeEach
    void setUp() {
        InheritedGenericWorkflow.SEEN.clear();
    }

    @Test
    @Timeout(60)
    @DisplayName("the first run and the replay after a node change see the same typed results")
    void firstRunAndReplayAreTyped() {
        var id = "inherited-1";
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ctx.getBean(MaestroClient.class)
                    .newWorkflow(InheritedGenericWorkflow.class, WorkflowOptions.builder().workflowId(id).build())
                    .startAsync(new InheritedFixtures.OrderRequest("a"));
            await().atMost(BOUND).until(() -> InheritedGenericWorkflow.SEEN.size() == 1
                    && store.getInstance(id).map(i -> i.status() == WorkflowStatus.WAITING_SIGNAL).orElse(false));
        });   // the context closes: the node stops with the run parked

        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var executor = ctx.getBean(WorkflowExecutor.class);
            executor.recoverWorkflows(ctx.getBean(WorkflowRegistrar.class).getRegistrations());
            await().atMost(BOUND).until(() -> InheritedGenericWorkflow.SEEN.size() == 2);
            executor.deliverSignal(id, "go", "go");
            await().atMost(BOUND).until(() ->
                    store.getInstance(id).map(i -> i.status().isTerminal()).orElse(false));
            assertThat(store.getInstance(id).orElseThrow().status()).isEqualTo(WorkflowStatus.COMPLETED);
        });

        assertThat(InheritedGenericWorkflow.SEEN).isEqualTo(List.of("a>order-a/order-a", "a>order-a/order-a"));
    }
}
