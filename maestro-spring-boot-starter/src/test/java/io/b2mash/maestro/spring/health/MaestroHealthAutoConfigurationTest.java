package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowSignal;
import io.b2mash.maestro.core.model.WorkflowTimer;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.config.MaestroAutoConfiguration;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-runner tests for {@link MaestroHealthAutoConfiguration} (Issue 8).
 *
 * <p>{@code CLAUDE.md} promises {@code io.b2mash.maestro.spring.health} /
 * {@code MaestroHealthIndicator}; these tests pin the auto-configuration
 * conditions — present only when Actuator's health classes are on the
 * classpath and Maestro itself has activated — and the indicator's
 * behaviour: {@code UP} with store/poller/running-count details when the
 * store is reachable, {@code DOWN} when it throws.
 */
@DisplayName("MaestroHealthAutoConfiguration")
class MaestroHealthAutoConfigurationTest {

    /** Base runner with no {@link WorkflowStore} bean — each test supplies its own. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MaestroAutoConfiguration.class, MaestroHealthAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("maestro.service-name=health-test");

    @Test
    @DisplayName("indicator bean is present when Actuator's HealthIndicator is on the classpath")
    void indicatorPresentWhenActuatorOnClasspath() {
        runner.withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MaestroHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("indicator bean is absent when Actuator's HealthIndicator is not on the classpath")
    void indicatorAbsentWithoutActuatorOnClasspath() {
        runner.withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(MaestroHealthIndicator.class);
                });
    }

    @Test
    @DisplayName("status is DOWN when the store throws")
    void downWhenStoreThrows() {
        runner.withBean(WorkflowStore.class, ThrowingWorkflowStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var indicator = context.getBean(MaestroHealthIndicator.class);
                    var health = indicator.health();

                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("store", "unreachable");
                });
    }

    @Test
    @DisplayName("status is UP with store/poller/running-count details when the store is reachable")
    void upWithDetailsWhenStoreReachable() {
        runner.withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var indicator = context.getBean(MaestroHealthIndicator.class);
                    var health = indicator.health();

                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails())
                            .containsEntry("store", "reachable")
                            .containsKeys("timerPollerRunning", "recoveryPollerRunning", "runningWorkflowCount");
                });
    }

    /** Always throws from {@link #getInstance(String)} to simulate an unreachable store. */
    private static final class ThrowingWorkflowStore implements WorkflowStore {

        private final InMemoryWorkflowStore delegate = new InMemoryWorkflowStore();

        @Override
        public WorkflowInstance createInstance(WorkflowInstance instance) {
            return delegate.createInstance(instance);
        }

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            throw new RuntimeException("store unreachable");
        }

        @Override
        public List<WorkflowInstance> getRecoverableInstances() {
            return delegate.getRecoverableInstances();
        }

        @Override
        public void updateInstance(WorkflowInstance instance) {
            delegate.updateInstance(instance);
        }

        @Override
        public void appendEvent(WorkflowEvent event) {
            delegate.appendEvent(event);
        }

        @Override
        public Optional<WorkflowEvent> getEventBySequence(UUID instanceId, int sequenceNumber) {
            return delegate.getEventBySequence(instanceId, sequenceNumber);
        }

        @Override
        public List<WorkflowEvent> getEvents(UUID instanceId) {
            return delegate.getEvents(instanceId);
        }

        @Override
        public void saveSignal(WorkflowSignal signal) {
            delegate.saveSignal(signal);
        }

        @Override
        public List<WorkflowSignal> getUnconsumedSignals(String workflowId, String signalName) {
            return delegate.getUnconsumedSignals(workflowId, signalName);
        }

        @Override
        public boolean markSignalConsumed(UUID signalId) {
            return delegate.markSignalConsumed(signalId);
        }

        @Override
        public void adoptOrphanedSignals(String workflowId, UUID instanceId) {
            delegate.adoptOrphanedSignals(workflowId, instanceId);
        }

        @Override
        public void saveTimer(WorkflowTimer timer) {
            delegate.saveTimer(timer);
        }

        @Override
        public List<WorkflowTimer> getDueTimers(Instant now, int batchSize) {
            return delegate.getDueTimers(now, batchSize);
        }

        @Override
        public Optional<WorkflowTimer> findTimer(UUID workflowInstanceId, String timerId) {
            return delegate.findTimer(workflowInstanceId, timerId);
        }

        @Override
        public boolean markTimerFired(UUID timerId) {
            return delegate.markTimerFired(timerId);
        }

        @Override
        public void markTimerCancelled(UUID timerId) {
            delegate.markTimerCancelled(timerId);
        }
    }
}
