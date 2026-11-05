package io.b2mash.maestro.test;

import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records, per workflow ID, what the engine did with each run: which activity
 * steps executed live, which replayed from history, how often the workflow was
 * re-adopted, and any stand-downs. One recorder is shared by every node a
 * {@link TestWorkflowEnvironment} builds, so the record spans crashes and
 * recoveries.
 *
 * <ul>
 *   <li>{@code activityStarted} → a live step. It fires <em>before</em> the
 *       activity executes, so an execution whose result was lost to a crash
 *       still counts.</li>
 *   <li>{@code activityCompleted}/{@code activityFailed} with
 *       {@code replayed = true} → a replayed step.</li>
 *   <li>{@code workflowResumed} → a recovery (re-adoption).</li>
 *   <li>{@code standDown} → a {@link StandDown}.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> callbacks arrive on workflow threads; every
 * structure is concurrent, and the accessors return snapshots.
 */
final class RunRecorder implements EngineObserver {

    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();

    @Override
    public void activityStarted(ActivityInfo a) {
        run(a.workflowId()).live.add(a.activityName());
    }

    @Override
    public void activityCompleted(ActivityInfo a, Duration duration, boolean replayed) {
        if (replayed) {
            run(a.workflowId()).replayed.add(a.activityName());
        }
    }

    @Override
    public void activityFailed(ActivityInfo a, Duration duration, String exceptionType, boolean replayed) {
        if (replayed) {
            run(a.workflowId()).replayed.add(a.activityName());
        }
    }

    @Override
    public void workflowResumed(WorkflowInfo w) {
        run(w.workflowId()).recoveries.incrementAndGet();
    }

    @Override
    public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
        run(workflowId).standDowns.add(new StandDown(reason, detail));
    }

    /** @return the activity steps that executed live, in start order */
    List<String> liveSteps(String workflowId) {
        return List.copyOf(run(workflowId).live);
    }

    /** @return the activity steps that replayed from history, in replay order */
    List<String> replayedSteps(String workflowId) {
        return List.copyOf(run(workflowId).replayed);
    }

    /** @return how many times the workflow was re-adopted by a recovering node */
    int recoveries(String workflowId) {
        return run(workflowId).recoveries.get();
    }

    /** @return the stand-downs, in the order they were reported */
    List<StandDown> standDowns(String workflowId) {
        return List.copyOf(run(workflowId).standDowns);
    }

    private Run run(String workflowId) {
        return runs.computeIfAbsent(workflowId, _ -> new Run());
    }

    private static final class Run {
        final List<String> live = new CopyOnWriteArrayList<>();
        final List<String> replayed = new CopyOnWriteArrayList<>();
        final List<StandDown> standDowns = new CopyOnWriteArrayList<>();
        final AtomicInteger recoveries = new AtomicInteger();
    }
}
