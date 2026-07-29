package io.b2mash.maestro.spring.health;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowInstance;
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

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-runner tests for {@link MaestroHealthAutoConfiguration} (Issue 8).
 *
 * <p>{@code CLAUDE.md} promises {@code io.b2mash.maestro.spring.health} /
 * {@code MaestroHealthIndicator}; these tests pin the auto-configuration
 * conditions — present only when Actuator's health classes are on the
 * classpath and Maestro itself has activated — and the indicator's
 * behaviour: {@code UP} only when the store is reachable and every
 * <em>enabled</em> poller that has actually started is running.
 *
 * <p>Three poller states matter and are tested separately:
 * <ul>
 *   <li><b>starting</b> — never started yet (the normal state right after
 *       boot, before the starter's {@code StartupRecoveryRunner} runs) —
 *       must not be {@code DOWN};</li>
 *   <li><b>running</b> — started and alive — {@code UP};</li>
 *   <li><b>dead</b> — started, then stopped running — a real fault,
 *       {@code DOWN}.</li>
 * </ul>
 * A poller disabled by configuration (e.g. {@code maestro.recovery.enabled=false})
 * is reported as {@code "disabled"} and never affects status, regardless of
 * whether it was ever started.
 *
 * <p>The timer and recovery pollers are only started by the starter's
 * {@code StartupRecoveryRunner} — an {@code ApplicationRunner} that a bare
 * {@link ApplicationContextRunner} never invokes — so tests that need a
 * poller running (or stopped after running) drive it directly via the
 * {@link WorkflowExecutor} bean, mirroring how
 * {@code MaestroAutoConfigurationConfigSeamsTest} drives the engine
 * directly through its real beans.
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
    @DisplayName("status is DOWN when the store throws, and poller/running-count details are still reported")
    void downWhenStoreThrows() {
        runner.withBean(WorkflowStore.class, ThrowingWorkflowStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var indicator = context.getBean(MaestroHealthIndicator.class);
                    var health = indicator.health();

                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails())
                            .containsEntry("store", "unreachable")
                            .containsKeys("timerPollerRunning", "recoveryPollerRunning", "runningWorkflowCount");
                });
    }

    @Test
    @DisplayName("status is UP with store/poller/running-count details when the store is reachable "
            + "and both pollers are running")
    void upWhenStoreReachableAndPollersRunning() {
        runner.withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var executor = context.getBean(WorkflowExecutor.class);
                    executor.startTimerPoller(Duration.ofSeconds(5), 100);
                    executor.startRecoveryPoller(Map.of(), Duration.ofSeconds(60));
                    try {
                        var indicator = context.getBean(MaestroHealthIndicator.class);
                        var health = indicator.health();

                        assertThat(health.getStatus()).isEqualTo(Status.UP);
                        assertThat(health.getDetails())
                                .containsEntry("store", "reachable")
                                .containsEntry("timerPollerRunning", true)
                                .containsEntry("recoveryPollerRunning", true)
                                .containsKey("runningWorkflowCount");
                    } finally {
                        executor.shutdown();
                    }
                });
    }

    @Test
    @DisplayName("status is NOT DOWN during the startup window, before pollers have started "
            + "(StartupRecoveryRunner hasn't run yet) — reported as \"starting\"")
    void notDownDuringStartupWindowBeforePollersStart() {
        runner.withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var indicator = context.getBean(MaestroHealthIndicator.class);
                    var health = indicator.health();

                    assertThat(health.getStatus())
                            .as("neither poller has started yet — this is every normal boot and every "
                                    + "rolling deploy, not a fault; must not be DOWN")
                            .isNotEqualTo(Status.DOWN);
                    assertThat(health.getDetails())
                            .containsEntry("timerPollerRunning", "starting")
                            .containsEntry("recoveryPollerRunning", "starting");
                });
    }

    @Test
    @DisplayName("status is DOWN once the timer poller (always enabled) has started and then stopped")
    void downWhenTimerPollerStartedThenStopped() {
        runner.withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .withPropertyValues("maestro.recovery.enabled=false") // isolate the assertion to the timer poller
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var executor = context.getBean(WorkflowExecutor.class);
                    executor.startTimerPoller(Duration.ofSeconds(5), 100);
                    executor.shutdown(); // stops the poller — "started, then died", a real fault

                    var indicator = context.getBean(MaestroHealthIndicator.class);
                    var health = indicator.health();

                    assertThat(health.getStatus())
                            .as("the poller started successfully and then stopped running — unlike "
                                    + "never having started, this is a genuine fault")
                            .isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("timerPollerRunning", false);
                });
    }

    @Test
    @DisplayName("status is DOWN once the (enabled) recovery poller has started and then stopped")
    void downWhenRecoveryPollerStartedThenStopped() {
        runner.withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var executor = context.getBean(WorkflowExecutor.class);
                    executor.startTimerPoller(Duration.ofSeconds(5), 100);
                    executor.startRecoveryPoller(Map.of(), Duration.ofSeconds(60));
                    executor.shutdown(); // stops both pollers — recovery specifically now "started, then died"

                    var indicator = context.getBean(MaestroHealthIndicator.class);
                    var health = indicator.health();

                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("recoveryPollerRunning", false);
                });
    }

    @Test
    @DisplayName("status stays UP when the recovery poller is disabled by configuration, "
            + "reported as \"disabled\" rather than \"starting\" or false")
    void upWhenRecoveryPollerDisabledByConfiguration() {
        runner.withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
                .withPropertyValues("maestro.recovery.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var executor = context.getBean(WorkflowExecutor.class);
                    executor.startTimerPoller(Duration.ofSeconds(5), 100);
                    // Recovery poller intentionally never started — disabled, not dead or starting.
                    try {
                        var indicator = context.getBean(MaestroHealthIndicator.class);
                        var health = indicator.health();

                        assertThat(health.getStatus()).isEqualTo(Status.UP);
                        assertThat(health.getDetails()).containsEntry("recoveryPollerRunning", "disabled");
                    } finally {
                        executor.shutdown();
                    }
                });
    }

    /** Always throws from {@link #getInstance(String)} to simulate an unreachable store. */
    private static final class ThrowingWorkflowStore extends DelegatingWorkflowStore {

        @Override
        public Optional<WorkflowInstance> getInstance(String workflowId) {
            throw new RuntimeException("store unreachable");
        }
    }
}
