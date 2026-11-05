package io.b2mash.maestro.test;

import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowFailureDetail;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Handle to a workflow started via {@link TestWorkflowEnvironment}.
 *
 * <p>Provides methods to deliver signals, query status, wait for results,
 * and inspect the event log for test assertions.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var handle = env.startWorkflow(OrderWorkflow.class, input);
 * handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
 * handle.signal("payment.result", paymentResult);
 *
 * var result = handle.getResult(OrderResult.class, Duration.ofSeconds(5));
 * assertEquals(WorkflowStatus.COMPLETED, handle.getStatus());
 * }</pre>
 *
 * <h2>Across crashes</h2>
 * <p>The handle follows its workflow across {@link TestWorkflowEnvironment#recover()}:
 * signals and queries go to whichever node is current, and
 * {@link #liveSteps()}, {@link #replayedSteps()}, {@link #recoveries()} and
 * {@link #standDowns()} accumulate over every node that ran the workflow.
 *
 * <p><b>Thread safety:</b> thread-safe; every method reads the shared store or
 * the environment's concurrent run record.
 */
public final class TestWorkflowHandle {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

    private final String workflowId;
    private final UUID instanceId;
    private final TestWorkflowEnvironment env;
    private final InMemoryWorkflowStore store;
    private final PayloadSerializer serializer;
    private final RunRecorder recorder;

    TestWorkflowHandle(
            String workflowId,
            UUID instanceId,
            TestWorkflowEnvironment env,
            InMemoryWorkflowStore store,
            PayloadSerializer serializer,
            RunRecorder recorder
    ) {
        this.workflowId = workflowId;
        this.instanceId = instanceId;
        this.env = env;
        this.store = store;
        this.serializer = serializer;
        this.recorder = recorder;
    }

    // ── Signal delivery ──────────────────────────────────────────────────

    /**
     * Delivers a signal to this workflow.
     *
     * <p>Persisted immediately. While the node is crashed the signal still
     * reaches the store — a signal arriving during an outage — and the
     * recovered run consumes it.
     *
     * @param signalName the signal name (e.g., {@code "payment.result"})
     * @param payload    the signal payload, or {@code null}
     */
    public void signal(String signalName, @Nullable Object payload) {
        env.currentExecutor().deliverSignal(workflowId, signalName, payload);
    }

    /**
     * Delivers a signal carrying a message id, as a production producer that
     * names its signals would — a second delivery with the same id is
     * suppressed, so a test can pin a workflow's behaviour under redelivery.
     *
     * @param signalName the signal name
     * @param payload    the signal payload, or {@code null}
     * @param messageId  the producer's identity for this logical signal, or
     *                   {@code null} for none (never deduplicated)
     * @return {@code true} if the signal was persisted; {@code false} if a
     *         signal with the same name and id was already stored
     */
    public boolean signal(String signalName, @Nullable Object payload, @Nullable String messageId) {
        // Same namespace as the starter's WorkflowHandle.signal(name, payload, id).
        return env.currentExecutor().deliverSignal(workflowId, signalName, payload,
                messageId == null ? null : "client:" + messageId);
    }

    // ── Result retrieval ─────────────────────────────────────────────────

    /**
     * Blocks until the workflow completes and returns the deserialized result.
     *
     * <p>Polls the store every 10ms until the workflow reaches a terminal
     * state. If the workflow fails, throws a {@link RuntimeException}
     * wrapping the failure details; use {@link #awaitTerminal(Duration)} and
     * {@link #getFailure()} to assert on a failure instead.
     *
     * @param <T>     the result type
     * @param type    the expected result class
     * @param timeout maximum time to wait
     * @return the workflow result
     * @throws RuntimeException  if the workflow failed
     * @throws TimeoutException  if the timeout elapses before completion
     */
    public <T> T getResult(Class<T> type, Duration timeout) throws TimeoutException {
        var instance = awaitTerminalInstance(timeout);

        if (instance.status() == WorkflowStatus.FAILED) {
            var output = instance.output();
            var detail = output != null ? output.toString() : "unknown";
            throw new RuntimeException("Workflow '%s' failed: %s".formatted(workflowId, detail));
        }

        return serializer.deserialize(instance.output(), type);
    }

    /**
     * Blocks until the workflow completes, ignoring the result.
     *
     * @param timeout maximum time to wait
     * @throws RuntimeException if the workflow failed
     * @throws TimeoutException if the timeout elapses before completion
     */
    public void awaitCompletion(Duration timeout) throws TimeoutException {
        var instance = awaitTerminalInstance(timeout);

        if (instance.status() == WorkflowStatus.FAILED) {
            var output = instance.output();
            var detail = output != null ? output.toString() : "unknown";
            throw new RuntimeException("Workflow '%s' failed: %s".formatted(workflowId, detail));
        }
    }

    /**
     * Blocks until the workflow reaches a terminal status and returns it —
     * {@code FAILED} included, without throwing, so a test can assert on a
     * failure with {@link #getFailure()}.
     *
     * @param timeout maximum time to wait
     * @return {@link WorkflowStatus#COMPLETED}, {@link WorkflowStatus#FAILED}
     *         or {@link WorkflowStatus#TERMINATED}
     * @throws TimeoutException if the timeout elapses first
     */
    public WorkflowStatus awaitTerminal(Duration timeout) throws TimeoutException {
        return awaitTerminalInstance(timeout).status();
    }

    /**
     * Blocks until the workflow's durable status is {@code status} — the
     * replacement for a hand-rolled polling loop before a signal or
     * {@code advanceTime}.
     *
     * <p>Waiting for a park ({@code WAITING_SIGNAL}, {@code WAITING_TIMER})
     * before {@link TestWorkflowEnvironment#advanceTime(Duration)} matters: a
     * timer that does not exist yet cannot be fired. A signal needs no such
     * wait — it is persisted and consumed whenever the workflow reaches its
     * await.
     *
     * @param status  a non-terminal status to wait for
     * @param timeout maximum time to wait
     * @throws IllegalArgumentException if {@code status} is terminal — use
     *                                  {@link #awaitTerminal(Duration)}
     * @throws IllegalStateException    if the workflow ends (terminal status)
     *                                  before reaching {@code status}
     * @throws TimeoutException         if the timeout elapses first
     */
    public void awaitStatus(WorkflowStatus status, Duration timeout) throws TimeoutException {
        if (status.isTerminal()) {
            throw new IllegalArgumentException(
                    "%s is terminal — use awaitTerminal(Duration), which waits for the terminal event too"
                            .formatted(status));
        }
        var deadline = Instant.now().plus(timeout);
        while (true) {
            var instance = instance();
            if (instance.status() == status) {
                return;
            }
            if (instance.status().isTerminal()) {
                var failure = getFailure().map(f -> ": " + f.exceptionType() + ": " + f.message()).orElse("");
                throw new IllegalStateException("Workflow '%s' ended %s%s while waiting for %s"
                        .formatted(workflowId, instance.status(), failure, status));
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new TimeoutException("Workflow '%s' did not reach %s within %s (current status: %s)"
                        .formatted(workflowId, status, timeout, instance.status()));
            }
            pause();
        }
    }

    // ── Status and identity ──────────────────────────────────────────────

    /**
     * Returns the current workflow status.
     *
     * @return the durable status in the store
     */
    public WorkflowStatus getStatus() {
        return instance().status();
    }

    /**
     * Returns why the workflow failed, if it has: the exception class name and
     * message the engine recorded when the workflow method threw (after its
     * saga compensations, if any, ran).
     *
     * @return the failure detail when the workflow is {@code FAILED}; empty otherwise
     */
    public Optional<WorkflowFailureDetail> getFailure() {
        var instance = instance();
        if (instance.status() != WorkflowStatus.FAILED || instance.output() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(serializer.deserialize(instance.output(), WorkflowFailureDetail.class));
    }

    /**
     * Returns the business workflow ID.
     *
     * @return the workflow ID
     */
    public String getWorkflowId() {
        return workflowId;
    }

    /**
     * Returns the workflow instance UUID.
     *
     * @return the instance ID
     */
    public UUID getInstanceId() {
        return instanceId;
    }

    // ── Event log ────────────────────────────────────────────────────────

    /**
     * Returns the workflow's memoization event log, ordered by sequence.
     *
     * <p>Useful for asserting that specific activities were executed,
     * signals were received, or timers were scheduled.
     *
     * @return the persisted events
     */
    public List<WorkflowEvent> getEvents() {
        return store.getEvents(instanceId);
    }

    // ── Execution record (across crashes) ────────────────────────────────

    /**
     * Returns every activity step this workflow executed live, in start order,
     * across every node — {@code "<ActivityGroup>.<method>"}, e.g.
     * {@code "InventoryActivities.reserve"}. Recorded when the execution
     * starts, so an execution whose result a crash lost still appears.
     *
     * @return the live step names
     */
    public List<String> liveSteps() {
        return recorder.liveSteps(workflowId);
    }

    /**
     * Returns every activity step whose recorded outcome (result or failure)
     * was replayed from history, in replay order, across every recovery.
     * Activities only: replayed signals, timers, side effects and version
     * markers are not listed.
     *
     * @return the replayed step names
     */
    public List<String> replayedSteps() {
        return recorder.replayedSteps(workflowId);
    }

    /**
     * @param step a step name, {@code "<ActivityGroup>.<method>"}
     * @return how many times {@code step} executed live
     */
    public int liveExecutions(String step) {
        return Collections.frequency(liveSteps(), step);
    }

    /**
     * @param step a step name, {@code "<ActivityGroup>.<method>"}
     * @return how many times {@code step}'s outcome was replayed
     */
    public int replays(String step) {
        return Collections.frequency(replayedSteps(), step);
    }

    /**
     * Returns how many times a recovering node re-adopted this workflow.
     *
     * @return the number of recoveries
     */
    public int recoveries() {
        return recorder.recoveries(workflowId);
    }

    /**
     * Returns the stand-downs reported for this workflow: runs that stopped
     * without recording an outcome because they could not follow the recorded
     * history (see {@link StandDown}).
     *
     * @return the stand-downs, oldest first
     */
    public List<StandDown> standDowns() {
        return recorder.standDowns(workflowId);
    }

    /**
     * Blocks until a run of this workflow stands down, and returns the first
     * stand-down.
     *
     * @param timeout maximum time to wait
     * @return the first stand-down
     * @throws TimeoutException if none is reported in time
     */
    public StandDown awaitStandDown(Duration timeout) throws TimeoutException {
        var deadline = Instant.now().plus(timeout);
        while (true) {
            var standDowns = standDowns();
            if (!standDowns.isEmpty()) {
                return standDowns.getFirst();
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new TimeoutException("Workflow '%s' did not stand down within %s (status: %s)"
                        .formatted(workflowId, timeout, getStatus()));
            }
            pause();
        }
    }

    // ── Query ────────────────────────────────────────────────────────────

    /**
     * Queries the running workflow by invoking a {@code @QueryMethod} on the
     * current node's workflow object.
     *
     * @param <T>        the result type
     * @param queryName  the query name
     * @param queryArg   the query argument, or {@code null}
     * @param resultType the expected result type
     * @return the query result
     */
    public <T> T query(String queryName, @Nullable Object queryArg, Class<T> resultType) {
        return env.currentExecutor().queryWorkflow(workflowId, queryName, queryArg, resultType);
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private WorkflowInstance instance() {
        return store.getInstance(workflowId)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow '%s' not found in store".formatted(workflowId)));
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a workflow", e);
        }
    }

    /**
     * Waits for the run to be finished in the durable log, not merely flagged as
     * finished on the instance row.
     *
     * <p>Until Issue 26 the engine finalised a run with two separate writes:
     * the instance row moved to {@code COMPLETED}/{@code FAILED} first, then
     * the matching {@code WORKFLOW_COMPLETED}/{@code WORKFLOW_FAILED} event was
     * appended, so returning on the status alone could hand the caller a log
     * one event short. The engine now writes both in one atomic
     * {@code WorkflowStore.completeInstance} call; waiting for the event stays,
     * because it is exactly what a caller of {@code getEvents()} depends on and
     * it fails loudly if a store ever breaks that contract.
     *
     * <p>{@link WorkflowStatus#TERMINATED} is exempt: terminating a workflow
     * appends no event to the log, so there is nothing there to wait for.
     *
     * <p>It also waits for the run to have <em>left</em> its node. The engine
     * writes the terminal row, then emits observer callbacks and the lifecycle
     * event, and only then deregisters the run — until it does, the run still
     * answers {@code query()}. Returning on the row alone would let a test that
     * queries right after {@code awaitTerminal} be answered by the finished run
     * instead of getting {@code WorkflowNotQueryableException}, as production
     * callers on any other node would.
     */
    private WorkflowInstance awaitTerminalInstance(Duration timeout) throws TimeoutException {
        var deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            var instance = instance();

            if (instance.status().isTerminal() && terminalEventLanded(instance.status()) && noNodeRunsIt()) {
                return instance;
            }

            pause();
        }

        var currentStatus = store.getInstance(workflowId)
                .map(i -> i.status().toString())
                .orElse("NOT_FOUND");
        throw new TimeoutException(
                "Workflow '%s' did not complete within %s (current status: %s)"
                        .formatted(workflowId, timeout, currentStatus));
    }

    /** @return whether no node of the environment still holds this workflow's run */
    private boolean noNodeRunsIt() {
        return env.nodeExecutors().stream().noneMatch(executor -> executor.isRunning(workflowId));
    }

    /**
     * @param status the terminal status already observed on the instance row
     * @return whether the event that closes the run is in the log — trivially
     *         {@code true} for {@link WorkflowStatus#TERMINATED}, which appends
     *         none
     */
    private boolean terminalEventLanded(WorkflowStatus status) {
        // Derived from the observed status rather than accepting EITHER terminal
        // event: a retried workflow that failed once and then completed would
        // otherwise be "finished" the moment the stale WORKFLOW_FAILED was seen,
        // before its WORKFLOW_COMPLETED landed. Stores strip failure memos on
        // retry, but this predicate does not depend on their doing so.
        var expected = switch (status) {
            case COMPLETED -> EventType.WORKFLOW_COMPLETED;
            case FAILED -> EventType.WORKFLOW_FAILED;
            default -> null;    // TERMINATED appends no event
        };
        if (expected == null) {
            return true;
        }
        return store.getEvents(instanceId).stream().anyMatch(e -> e.eventType() == expected);
    }
}
