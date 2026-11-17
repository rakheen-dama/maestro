package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.client.WorkflowOptions;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * WP5 D-5 and D-6 through the real auto-configuration: Maestro's handler
 * stops asynchronously in the web server's graceful-shutdown phase, so the
 * web server's drain starts alongside Maestro's; a {@code context.pause()}
 * leaves the engine running; and the engine finishes draining before the
 * beans it was built on are destroyed, even when Spring's phase times out
 * first.
 */
@DisplayName("Maestro's shutdown inside Spring's lifecycle")
@ExtendWith(OutputCaptureExtension.class)
class GracefulShutdownLifecycleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaestroAutoConfiguration.class))
            .withUserConfiguration(MaestroAutoConfigurationConfigSeamsTest.TestWorkflowConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowStore.class, InMemoryWorkflowStore::new)
            .withPropertyValues("maestro.service-name=graceful-shutdown-test");

    @Test
    @DisplayName("the web server's graceful drain starts while Maestro drains, not after it (3.6)")
    void theWebServersGracefulDrainStartsWhileMaestroDrains() {
        var standIn = new WebStandIn();
        runner.withPropertyValues("maestro.shutdown.timeout=1s").run(context -> {
            assertThat(context).hasNotFailed();
            // Registered the way Boot registers its webServerGracefulShutdown: a manual singleton.
            context.getSourceApplicationContext().getBeanFactory()
                    .registerSingleton("webServerGracefulShutdown", standIn);
            startBlockingWorkflow(context.getBean(MaestroClient.class),
                    context.getBean(MaestroAutoConfigurationConfigSeamsTest.BlockingProbe.class), "web-drain-1");

            var closeStartedAt = System.nanoTime();
            context.getSourceApplicationContext().close();

            assertThat(standIn.stopCalledAt).as("the web server's stop(callback) was called").isNotZero();
            assertThat(Duration.ofNanos(standIn.stopCalledAt - closeStartedAt)).isLessThan(Duration.ofMillis(500));
        });
    }

    @Test
    void pausingTheContextLeavesTheEngineRunning() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.getSourceApplicationContext().pause();
            assertThatCode(() -> context.getBean(MaestroClient.class)
                    .newWorkflow(MaestroAutoConfigurationConfigSeamsTest.ParkingWorkflow.class, options("pause-1"))
                    .startAsync(null)).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("the engine finishes draining before the beans it was built on are destroyed, even when the phase times out")
    void theEngineDrainsBeforeTheBeansItIsBuiltOnAreDestroyed(CapturedOutput output) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LifecycleAutoConfiguration.class, MaestroAutoConfiguration.class))
                .withUserConfiguration(PoolBackedStoreConfiguration.class,
                        MaestroAutoConfigurationConfigSeamsTest.TestWorkflowConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("maestro.service-name=graceful-shutdown-test",
                        "spring.lifecycle.timeout-per-shutdown-phase=200ms",
                        "maestro.shutdown.timeout=1s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    startBlockingWorkflow(context.getBean(MaestroClient.class),
                            context.getBean(MaestroAutoConfigurationConfigSeamsTest.BlockingProbe.class),
                            "drain-before-pool-1");
                });

        var out = output.getOut();
        var engineDone = out.indexOf("Maestro shutdown hit its 1000 ms deadline");
        var poolClosed = out.indexOf("RECORDING POOL CLOSED");
        assertThat(engineDone).as(out).isNotNegative();
        assertThat(poolClosed).as(out).isGreaterThan(engineDone);
    }

    private static void startBlockingWorkflow(MaestroClient client,
                                              MaestroAutoConfigurationConfigSeamsTest.BlockingProbe probe,
                                              String workflowId) throws InterruptedException {
        client.newWorkflow(MaestroAutoConfigurationConfigSeamsTest.BlockingWorkflow.class, options(workflowId))
                .startAsync(null);
        assertThat(probe.entered.await(10, TimeUnit.SECONDS))
                .as("the workflow must be in flight before the context closes")
                .isTrue();
    }

    private static WorkflowOptions options(String workflowId) {
        return WorkflowOptions.builder().workflowId(workflowId).build();
    }

    /**
     * Stands in for Boot's {@code WebServerGracefulShutdownLifecycle}: same
     * phase, an asynchronous stop that calls back at once, not pauseable.
     * It records when Spring asked it to stop.
     */
    static final class WebStandIn implements SmartLifecycle {
        volatile long stopCalledAt;
        private volatile boolean running = true;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public void stop(Runnable callback) {
            stopCalledAt = System.nanoTime();
            running = false;
            callback.run();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return SmartLifecycle.DEFAULT_PHASE - 1024;
        }

        @Override
        public boolean isPauseable() {
            return false;
        }
    }

    /** Stands in for a connection pool: prints when it is closed. */
    static final class RecordingPool {
        /** Bean destroy method. */
        void close() {
            System.out.println("RECORDING POOL CLOSED");
        }
    }

    /**
     * The store takes its pool through an {@link ObjectProvider}, as Maestro's
     * Postgres beans take their {@code DataSource}: no dependency link is
     * registered, so destruction order follows creation order alone.
     */
    @Configuration(proxyBeanMethods = false)
    static class PoolBackedStoreConfiguration {

        @Bean(destroyMethod = "close")
        RecordingPool recordingPool() {
            return new RecordingPool();
        }

        @Bean
        WorkflowStore workflowStore(ObjectProvider<RecordingPool> pool) {
            pool.getObject();
            return new InMemoryWorkflowStore();
        }
    }
}
