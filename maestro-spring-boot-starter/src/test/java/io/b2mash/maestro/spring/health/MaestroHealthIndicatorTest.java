package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.SimpleHttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.SimpleStatusAggregator;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Status;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct (non-Spring-context) unit tests for {@link MaestroHealthIndicator},
 * covering the bounded store probe from Issue 8's follow-up review: a
 * degraded — merely slow, not cleanly failing — store must not be able to
 * hang the indicator, and by extension {@code /actuator/health}.
 * Since 3.5a a probe that does not finish within the bound reports {@code UNKNOWN}: a busy pool is not a broken store.
 */
@DisplayName("MaestroHealthIndicator")
class MaestroHealthIndicatorTest {

    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        var store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = WorkflowExecutor.builder(store, serializer, "health-indicator-test").build();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("returns promptly, bounded by STORE_PROBE_TIMEOUT, when the store call blocks indefinitely, and reports UNKNOWN")
    void returnsPromptlyWhenStoreBlocksIndefinitely() {
        var indicator = new MaestroHealthIndicator(new BlockingWorkflowStore(), executor, true, true);

        var start = Instant.now();
        var health = indicator.health();
        var elapsed = Duration.between(start, Instant.now());

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("store", "timed out");
        assertThat(elapsed)
                .as("must not hang on a store call that never returns — bounded by "
                        + MaestroHealthIndicator.STORE_PROBE_TIMEOUT + " plus scheduling slack, "
                        + "not indefinitely")
                .isLessThan(MaestroHealthIndicator.STORE_PROBE_TIMEOUT.plusSeconds(5));
    }

    @Test
    void aStoreThatThrowsWithinTheBoundIsDown() {
        var health = new MaestroHealthIndicator(new ThrowingWorkflowStore(), executor, true, true).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("store", "unreachable");
    }

    @Test
    void aTimedOutProbeDoesNotHideADeadLoop() {
        executor.startTimerPoller(Duration.ofSeconds(5), 100);
        executor.shutdown();                                   // started, then stopped: a fault
        var health = new MaestroHealthIndicator(new BlockingWorkflowStore(), executor, false, false).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("store", "timed out").containsEntry("timerPollerRunning", false);
    }

    /**
     * Pins the Spring Boot defaults that Operations §14 states: an {@code UNKNOWN}
     * {@code maestro} next to an {@code UP} member leaves the aggregate {@code UP},
     * and {@code UNKNOWN} itself answers 200 — only {@code DOWN} and
     * {@code OUT_OF_SERVICE} answer 503.
     */
    @Test
    void bootsDefaultsLeaveAnUnknownMaestroAt200() {
        assertThat(StatusAggregator.getDefault().getAggregateStatus(Set.of(Status.UP, Status.UNKNOWN)))
                .isEqualTo(Status.UP);
        assertThat(HttpCodeStatusMapper.DEFAULT.getStatusCode(Status.UNKNOWN)).isEqualTo(200);
        assertThat(HttpCodeStatusMapper.DEFAULT.getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(HttpCodeStatusMapper.DEFAULT.getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
    }

    /**
     * Pins what Operations §14 tells a host that wants a 503: map {@code unknown}
     * ({@code management.endpoint.health.status.http-mapping.unknown=503}), and,
     * for an aggregate or group that has an {@code UP} member too, order
     * {@code UNKNOWN} above {@code UP}
     * ({@code management.endpoint.health.status.order=DOWN,OUT_OF_SERVICE,UNKNOWN,UP}).
     */
    @Test
    void aHostCanMapUnknownTo503() {
        assertThat(new SimpleHttpCodeStatusMapper(Map.of("unknown", 503)).getStatusCode(Status.UNKNOWN))
                .isEqualTo(503);
        assertThat(new SimpleStatusAggregator("DOWN", "OUT_OF_SERVICE", "UNKNOWN", "UP")
                .getAggregateStatus(Set.of(Status.UP, Status.UNKNOWN)))
                .isEqualTo(Status.UNKNOWN);
    }

    /** Refuses the probe at once, as a store whose database rejects connections does. */
    private static final class ThrowingWorkflowStore extends DelegatingWorkflowStore {

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            throw new IllegalStateException("connection refused");
        }
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
