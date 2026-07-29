package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct (non-Spring-context) unit tests for {@link MaestroHealthIndicator},
 * covering the bounded store probe from Issue 8's follow-up review: a
 * degraded — merely slow, not cleanly failing — store must not be able to
 * hang the indicator, and by extension {@code /actuator/health}.
 */
@DisplayName("MaestroHealthIndicator")
class MaestroHealthIndicatorTest {

    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        var store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "health-indicator-test");
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("returns promptly, bounded by STORE_PROBE_TIMEOUT, when the store call blocks indefinitely")
    void returnsPromptlyWhenStoreBlocksIndefinitely() {
        var indicator = new MaestroHealthIndicator(new BlockingWorkflowStore(), executor, true);

        var start = Instant.now();
        var health = indicator.health();
        var elapsed = Duration.between(start, Instant.now());

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("store", "timed out");
        assertThat(elapsed)
                .as("must not hang on a store call that never returns — bounded by "
                        + MaestroHealthIndicator.STORE_PROBE_TIMEOUT + " plus scheduling slack, "
                        + "not indefinitely")
                .isLessThan(MaestroHealthIndicator.STORE_PROBE_TIMEOUT.plusSeconds(5));
    }

    /** Blocks forever in {@link #getInstance(String)} to simulate a hung (degraded, not failed) store. */
    private static final class BlockingWorkflowStore extends DelegatingWorkflowStore {

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            try {
                new CountDownLatch(1).await(); // never counts down — blocks until interrupted
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }
}
