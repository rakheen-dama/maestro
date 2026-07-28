package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link StartupRecoveryRunner}'s recovery-poller startup.
 *
 * <p>Whether the poller was started is observed through
 * {@link WorkflowExecutor#startRecoveryPoller}, which throws if the poller
 * is already running.
 */
class StartupRecoveryRunnerTest {

    private WorkflowExecutor executor;
    private MaestroProperties properties;
    private StartupRecoveryRunner runner;

    @BeforeEach
    void setUp() {
        var store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "test-service");
        properties = new MaestroProperties();
        properties.setServiceName("test-service");
        runner = new StartupRecoveryRunner(executor, new WorkflowRegistrar(executor), properties);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("starts the periodic recovery poller by default")
    void startsRecoveryPollerByDefault() {
        runner.run(null);

        assertThrows(IllegalStateException.class,
                () -> executor.startRecoveryPoller(Map.of(), Duration.ofSeconds(60)),
                "the runner must have already started the recovery poller");
    }

    @Test
    @DisplayName("does not start the recovery poller when maestro.recovery.enabled=false")
    void skipsRecoveryPollerWhenDisabled() {
        properties.setRecovery(new MaestroProperties.RecoveryProperties(false, Duration.ofSeconds(60)));

        runner.run(null);

        assertDoesNotThrow(
                () -> executor.startRecoveryPoller(Map.of(), Duration.ofSeconds(60)),
                "the poller slot must still be free when recovery polling is disabled");
    }
}
