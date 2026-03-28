package io.maestro.spring.config;

import io.maestro.core.engine.WorkflowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/**
 * Recovers workflows that were active when the service last stopped
 * and starts the background timer poller.
 *
 * <p>Runs as an {@link ApplicationRunner} after the full context refresh,
 * ensuring all workflow registrations are available (populated by
 * {@link WorkflowRegistrar} during the {@code SmartInitializingSingleton} phase).
 *
 * <p>Ordered with {@code HIGHEST_PRECEDENCE + 10} so recovery happens
 * before any user-defined {@code ApplicationRunner} beans.
 */
public class StartupRecoveryRunner implements ApplicationRunner, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(StartupRecoveryRunner.class);

    private final WorkflowExecutor executor;
    private final WorkflowRegistrar registrar;
    private final MaestroProperties properties;

    public StartupRecoveryRunner(
            WorkflowExecutor executor,
            WorkflowRegistrar registrar,
            MaestroProperties properties
    ) {
        this.executor = executor;
        this.registrar = registrar;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 1. Recover workflows that were in-flight during last shutdown/crash
        var registrations = registrar.getRegistrations();
        var recovered = executor.recoverWorkflows(registrations);
        logger.info("Maestro startup recovery complete: {} workflow(s) recovered", recovered);

        // 2. Start the timer poller for durable sleep/timer functionality
        var timer = properties.getTimer();
        executor.startTimerPoller(timer.pollInterval(), timer.batchSize());
        logger.info("Maestro timer poller started (interval={}, batchSize={})",
                timer.pollInterval(), timer.batchSize());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
