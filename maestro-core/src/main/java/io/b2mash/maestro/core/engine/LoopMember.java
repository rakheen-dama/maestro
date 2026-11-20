package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.loop.LoopSupervisor;
import org.jspecify.annotations.Nullable;

/**
 * A supervised loop as a member of the executor's {@link LifecycleRegistry}
 * (P1 task 3). It joins before its supervisor exists, so a stop request may
 * arrive first: it is remembered and applied when the supervisor is
 * {@linkplain #attach attached}.
 *
 * <p>Thread-safe: {@link #requestStop()} and {@link #attach} may race; both
 * fields are volatile and each side writes its own field before it reads the
 * other's, so at least one of them sees the other and the supervisor is asked
 * to stop.
 */
final class LoopMember implements LifecycleRegistry.Member {

    private final String name;
    private volatile @Nullable LoopSupervisor supervisor;
    private volatile boolean stopRequested;

    LoopMember(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    /** Asks the loop to stop: at once if its supervisor is attached, else as it is attached. Never interrupts. */
    @Override
    public void requestStop() {
        stopRequested = true;
        var attached = supervisor;
        if (attached != null) {
            attached.requestStop();
        }
    }

    /** Attaches the started supervisor, applying a stop request that came first. */
    void attach(LoopSupervisor started) {
        supervisor = started;
        if (stopRequested) {
            started.requestStop();
        }
    }
}
