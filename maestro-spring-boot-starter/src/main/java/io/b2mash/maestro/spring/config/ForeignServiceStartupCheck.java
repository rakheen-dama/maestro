package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.ForeignServiceCheckMode;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Objects;

/**
 * Runs the shared-table check ({@link WorkflowExecutor#checkForeignServices})
 * once, at context refresh: after every singleton exists and before any
 * lifecycle bean starts, so before a Kafka listener consumes and before
 * {@link StartupRecoveryRunner} adopts a workflow (pilot readiness P3;
 * Issues 39, 78, 79).
 *
 * <p>Under {@code maestro.store.foreign-service-check=fail} (the default), active
 * workflow instances under another {@code maestro.service-name} fail context
 * refresh with {@code ForeignServiceRowsException}; {@code warn} logs them;
 * {@code off} skips the read. The read is bounded (5 seconds), and a read that
 * fails or times out is inconclusive: one {@code WARN}, and startup continues.
 *
 * <p><b>Thread safety:</b> Spring calls {@link #afterSingletonsInstantiated()}
 * once, on the refreshing thread; the instance holds no mutable state.
 */
public class ForeignServiceStartupCheck implements SmartInitializingSingleton {

    private final WorkflowExecutor executor;
    private final ForeignServiceCheckMode mode;

    /**
     * @param executor the engine whose store and service name are checked
     * @param mode     {@code maestro.store.foreign-service-check}
     */
    public ForeignServiceStartupCheck(WorkflowExecutor executor, ForeignServiceCheckMode mode) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    @Override
    public void afterSingletonsInstantiated() {
        executor.checkForeignServices(mode);
    }
}
