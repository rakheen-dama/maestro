package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/**
 * Starts Maestro's background loops once the context is refreshed: workflow
 * recovery, the timer poller, the signal janitor and (unless disabled) the
 * retention sweeper.
 *
 * <p>Recovery starts first. With {@code maestro.testing.recovery.enabled=true} (the
 * default; the flag is TESTING ONLY, not a supported production setting) that is the recovery poller, whose first pass — the startup
 * recovery of workflows that were active when the service last stopped —
 * runs at once on the poller's own thread and is gated by task-queue
 * capacity. With {@code false} it is a one-shot <em>ungated</em> pass on its
 * own thread, retried every {@code maestro.recovery.poll-interval} until it
 * completes ({@link WorkflowExecutor#startOneShotRecovery}).
 *
 * <p>The shared-table check (active workflows under another
 * {@code maestro.service-name}) is not started here: it runs earlier, at
 * context refresh, in {@link ForeignServiceStartupCheck}, so a misconfigured
 * node fails before it adopts anything.
 *
 * <p>The runner only starts loops. It adopts nothing itself; keep it that way:
 * anything slow added here delays readiness. So {@code run} returns while the
 * startup pass is still in progress, and a store failure in that pass is
 * logged and retried, never thrown from here.
 *
 * <p>Runs as an {@link ApplicationRunner} after the full context refresh,
 * ensuring all workflow registrations are available (populated by
 * {@link WorkflowRegistrar} during the {@code SmartInitializingSingleton} phase).
 *
 * <p>Ordered with {@code HIGHEST_PRECEDENCE + 10} so recovery starts before
 * any user-defined {@code ApplicationRunner} beans.
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
        // 1. Start recovery — never run it here. The poller's first pass, or
        // the one-shot pass when the poller is disabled, adopts the workflows
        // that were in flight at the last shutdown/crash on its own thread.
        var registrations = registrar.getRegistrations();
        var recovery = properties.getRecovery();
        if (properties.getTesting().recovery().enabled()) {
            executor.startRecoveryPoller(registrations, recovery.pollInterval());
            logger.info("Maestro recovery poller started (interval={}); its first pass runs now, on its own thread",
                    recovery.pollInterval());
        } else {
            executor.startOneShotRecovery(registrations, recovery.pollInterval());
            logger.info("Maestro recovery poller disabled (maestro.testing.recovery.enabled=false, TESTING ONLY); one startup recovery "
                    + "pass runs on its own thread, retried every {} until it completes", recovery.pollInterval());
        }

        // 2. Start the timer poller for durable sleep/timer functionality
        var timer = properties.getTimer();
        executor.startTimerPoller(timer.pollInterval(), timer.batchSize());
        logger.info("Maestro timer poller started (interval={}, batchSize={})",
                timer.pollInterval(), timer.batchSize());

        // 2b. Start the signal janitor — purges unconsumed signals addressed
        // to workflows that finished long ago, and orphans no workflow can
        // consume any more. Runs on every node; the purge is idempotent.
        var signal = properties.getSignal();
        executor.startSignalJanitor(signal.janitorInterval(), signal.terminalRetention(), signal.orphanRetention());
        logger.info("Maestro signal janitor started (interval={}, terminalRetention={}, orphanRetention={})",
                signal.janitorInterval(), signal.terminalRetention(), signal.orphanRetention());

        // 2c. Start the retention sweeper — purges workflows that finished
        // longer ago than their status's retention, history included. Only
        // the elected leader for this service actually purges.
        var retention = properties.getRetention();
        if (retention.enabled()) {
            executor.startRetentionSweeper(new WorkflowExecutor.RetentionSettings(
                    retention.interval(), retention.completedRetention(), retention.terminatedRetention(),
                    retention.failedRetention(), retention.batchSize(), retention.maxBatchesPerSweep()));
            logger.info("Maestro retention sweeper started (interval={}, completedRetention={}, "
                            + "terminatedRetention={}, failedRetention={}, batchSize={}, maxBatchesPerSweep={})",
                    retention.interval(), retention.completedRetention(), retention.terminatedRetention(),
                    retention.failedRetention(), retention.batchSize(), retention.maxBatchesPerSweep());
        } else {
            logger.info("Maestro retention sweeper disabled (maestro.retention.enabled=false) — "
                    + "finished workflows are kept indefinitely");
        }

    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
