package io.maestro.spring.config;

import io.maestro.core.engine.WorkflowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Handles graceful shutdown of the Maestro workflow engine during
 * Spring context closure.
 *
 * <p>Delegates to {@link WorkflowExecutor#shutdown()} which:
 * <ul>
 *   <li>Stops the timer poller</li>
 *   <li>Stops accepting new workflows</li>
 *   <li>Unparks all waiting/sleeping workflows</li>
 *   <li>Waits up to 30 seconds for in-flight workflows to complete</li>
 * </ul>
 *
 * <h2>Phase Ordering</h2>
 * <p>Phase {@code Integer.MAX_VALUE - 1024}: stops after the web server
 * (phase {@code MAX_VALUE - 2048}) but before Spring destroys beans
 * (including the {@code DataSource}). This ensures in-flight workflows
 * can persist their final state before the database connection closes.
 */
public class GracefulShutdownHandler implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final WorkflowExecutor executor;
    private volatile boolean running;

    public GracefulShutdownHandler(WorkflowExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        logger.info("Maestro graceful shutdown initiated");
        executor.shutdown();
        logger.info("Maestro graceful shutdown complete");
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1024;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
