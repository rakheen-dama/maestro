package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.exception.MaestroControlFlowError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Shuts the Maestro workflow engine down when the Spring context closes, by
 * calling {@link WorkflowExecutor#shutdown()}: stop taking work, drain the
 * runs in flight, stop renewing instance locks, flush lifecycle events — all
 * within one deadline, {@code maestro.shutdown.timeout}.
 *
 * <h2>Phase</h2>
 * <p>{@code Integer.MAX_VALUE - 1024}, Spring Boot's
 * {@code WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE}. The handler
 * stops in the same phase as the web server's graceful drain and alongside
 * it, before the web server itself stops ({@code MAX_VALUE - 2048}) and
 * before any bean — the {@code DataSource} included — is destroyed. The
 * host's {@code @KafkaListener} containers (phase {@code MAX_VALUE - 100})
 * have stopped before it.
 *
 * <h2>Asynchronous</h2>
 * <p>{@link #stop(Runnable)} returns at once: the engine shuts down on a
 * virtual thread named {@code maestro-shutdown}, which calls Spring back when
 * {@link WorkflowExecutor#shutdown()} has returned — within
 * {@code maestro.shutdown.timeout} plus a few milliseconds — and also when it
 * threw. Spring waits for the callback up to
 * {@code spring.lifecycle.timeout-per-shutdown-phase}; if that passes first,
 * Spring moves on, and the executor's own destroy method (a second
 * {@code shutdown()} call) still waits for the drain, so the beans the engine
 * was built on outlive it.
 *
 * <h2>Not pauseable</h2>
 * <p>{@link #isPauseable()} is {@code false}, like the web server's
 * lifecycle: the engine's shutdown is one-way, so a
 * {@code ConfigurableApplicationContext.pause()} leaves the engine running
 * instead of shutting it down for good.
 *
 * <h2>One-way</h2>
 * <p>Once stopped, the engine cannot be restarted: {@link #start()} after
 * {@link #stop()} does not bring it back.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe: Spring calls it from the context's lifecycle thread, and
 * the asynchronous stop runs on its own virtual thread.
 */
public class GracefulShutdownHandler implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final Runnable engineShutdown;
    private volatile boolean running;

    /**
     * Creates a handler that shuts down the given executor.
     *
     * @param executor the engine to shut down when the context closes
     */
    public GracefulShutdownHandler(WorkflowExecutor executor) {
        this(executor::shutdown);
    }

    /**
     * Test seam: a handler that runs {@code engineShutdown} in place of
     * {@link WorkflowExecutor#shutdown()}.
     *
     * @param engineShutdown the engine's shutdown
     */
    GracefulShutdownHandler(Runnable engineShutdown) {
        this.engineShutdown = engineShutdown;
    }

    @Override
    public void start() {
        running = true;
    }

    /**
     * Shuts the engine down synchronously: returns once
     * {@link WorkflowExecutor#shutdown()} has returned. A throwable from it
     * propagates.
     */
    @Override
    public void stop() {
        running = false;
        logger.info("Maestro graceful shutdown initiated");
        engineShutdown.run();
        logger.info("Maestro graceful shutdown complete");
    }

    /**
     * Starts the engine's shutdown on the virtual thread {@code maestro-shutdown}
     * and returns at once. {@code callback} runs on that thread when the
     * shutdown has returned or thrown. If the thread cannot be started, the
     * same work runs on the calling thread.
     *
     * @param callback Spring's callback, run exactly once
     */
    @Override
    public void stop(Runnable callback) {
        running = false;
        logger.info("Maestro graceful shutdown initiated");
        Runnable body = () -> {
            try {
                engineShutdown.run();
                logger.info("Maestro graceful shutdown complete");
            } catch (Throwable t) {
                logger.error("Maestro graceful shutdown failed: {}", t.toString(), t);
                if (t instanceof MaestroControlFlowError controlFlow) {
                    throw controlFlow;
                }
            } finally {
                callback.run();
            }
        };
        try {
            Thread.ofVirtual().name("maestro-shutdown").start(body);
        } catch (RuntimeException | OutOfMemoryError cannotStart) {
            logger.warn("Could not start the maestro-shutdown thread ({}); shutting down on the calling thread",
                    cannotStart.toString());
            body.run();
        }
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

    /**
     * {@code false}: a context pause leaves the engine running, because its
     * shutdown cannot be undone.
     *
     * @return {@code false}
     */
    @Override
    public boolean isPauseable() {
        return false;
    }
}
