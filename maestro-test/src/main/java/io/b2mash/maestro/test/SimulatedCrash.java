package io.b2mash.maestro.test;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A node crash armed on a {@link TestWorkflowEnvironment} — by
 * {@link TestWorkflowEnvironment#crashAfter(int)},
 * {@link TestWorkflowEnvironment#crashAfterPersisting},
 * {@link TestWorkflowEnvironment#crashBeforePersisting} or
 * {@link TestWorkflowEnvironment#crashNow()} — and the handle to recover from
 * it.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var crash = env.crashAfter(2);                  // die once two activities have completed
 * var handle = env.startWorkflow(OrderWorkflow.class, input);
 * crash.recover();                                // wait for it, then restart the node
 *
 * handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 * assertEquals(2, handle.replayedSteps().size());
 * }</pre>
 *
 * <p>The crash fires on the workflow thread that persists the matching event
 * and kills the whole simulated node: every workflow running on it stops at
 * its next store write, writing nothing. {@link #recover()} then starts a
 * fresh node over the same store, which re-adopts every active workflow and
 * replays its history.
 *
 * <p><b>Thread safety:</b> thread-safe; the crash is triggered on a workflow
 * thread and observed from the test thread.
 */
public final class SimulatedCrash {

    /** How long {@link #recover()} waits for an armed crash to be reached. */
    static final Duration RECOVER_WAIT = Duration.ofSeconds(10);

    private final TestWorkflowEnvironment env;
    private final String description;
    private final CountDownLatch triggered = new CountDownLatch(1);
    private volatile @Nullable WorkflowEvent event;
    private volatile @Nullable String workflowId;
    private volatile @Nullable WorkflowExecutor deadExecutor;

    SimulatedCrash(TestWorkflowEnvironment env, String description) {
        this.env = env;
        this.description = description;
    }

    /**
     * Records that the crash fired. Called once, on the crashing thread (or the
     * test thread for {@code crashNow()}).
     *
     * @param event      the event whose persistence triggered it, or {@code null}
     * @param workflowId the workflow whose thread triggered it, or {@code null}
     * @param executor   the executor of the node that died
     */
    void trigger(@Nullable WorkflowEvent event, @Nullable String workflowId, WorkflowExecutor executor) {
        this.event = event;
        this.workflowId = workflowId;
        this.deadExecutor = executor;
        triggered.countDown();
    }

    /**
     * Returns whether the crash has fired.
     *
     * @return {@code true} once the node has died
     */
    public boolean hasCrashed() {
        return triggered.getCount() == 0;
    }

    /**
     * Waits until the crash has fired and the workflow that triggered it has
     * finished unwinding on the dead node.
     *
     * <p>If the crash is not reached within {@code timeout} it is disarmed —
     * so another can be armed — and this throws.
     *
     * @param timeout how long to wait
     * @throws AssertionError if the crash was not reached in time — usually the
     *                        workflow finished, or parked, before the armed
     *                        point
     */
    public void awaitCrash(Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        try {
            if (!triggered.await(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                env.disarm(this);
                // The trigger may have raced the disarm.
                if (!hasCrashed()) {
                    throw new AssertionError(("Simulated crash armed but never reached within %s (%s) — "
                            + "the workflow finished or parked before the armed point; it is now disarmed")
                            .formatted(timeout, description));
                }
            }
            var id = workflowId;
            var executor = deadExecutor;
            while (id != null && executor != null && executor.isRunning(id)) {
                if (Instant.now().isAfter(deadline)) {
                    throw new AssertionError("Workflow '%s' was still unwinding on the crashed node after %s"
                            .formatted(id, timeout));
                }
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a simulated crash", e);
        }
    }

    /**
     * Waits for the crash (up to 10 seconds), then recovers:
     * {@link TestWorkflowEnvironment#recover()}.
     *
     * @return the number of workflows the new node re-adopted
     * @throws AssertionError if the crash was armed but never reached
     */
    public int recover() {
        awaitCrash(RECOVER_WAIT);
        return env.recover();
    }

    /**
     * Waits for the crash (up to 10 seconds), then recovers onto new code:
     * {@link TestWorkflowEnvironment#recover(Class[])}.
     *
     * @param workflowClasses replacement workflow classes, matched to the
     *                        started workflows by workflow type name
     * @return the number of workflows the new node re-adopted
     * @throws AssertionError if the crash was armed but never reached
     */
    public int recover(Class<?>... workflowClasses) {
        awaitCrash(RECOVER_WAIT);
        return env.recover(workflowClasses);
    }

    /**
     * Returns the event whose persistence (or attempted persistence) fired the
     * crash.
     *
     * @return the event, or {@code null} before the crash and for
     *         {@code crashNow()}
     */
    public @Nullable WorkflowEvent event() {
        return event;
    }

    /**
     * Returns the workflow whose thread fired the crash.
     *
     * @return the workflow ID, or {@code null} before the crash and for
     *         {@code crashNow()}
     */
    public @Nullable String workflowId() {
        return workflowId;
    }

    /** @return what the crash was armed for, e.g. {@code "after 2 completed activities"} */
    String description() {
        return description;
    }

    @Override
    public String toString() {
        return "SimulatedCrash[" + description + (hasCrashed() ? ", crashed" : ", armed") + "]";
    }
}
