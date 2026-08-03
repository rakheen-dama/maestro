package io.b2mash.maestro.integration.support;

import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the {@link EngineObserver} callbacks the integration suites assert
 * on, in arrival order.
 *
 * <p>Deliberately narrow: the callbacks a stand-down must fire, and the three
 * outcome callbacks it must <em>not</em>. A wider recorder would invite tests
 * to assert on incidental traffic.
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe — callbacks arrive from workflow virtual threads and poller
 * threads while the test thread reads.
 */
public final class RecordingObserver implements EngineObserver {

    /** One recorded {@code standDown} call. */
    public record StandDownCall(StandDownReason reason, String workflowId,
                                @Nullable String detail) {}

    private final List<String> callbackNames = new CopyOnWriteArrayList<>();
    private final List<StandDownCall> standDowns = new CopyOnWriteArrayList<>();
    private final List<String> failed = new CopyOnWriteArrayList<>();
    private final List<String> compensating = new CopyOnWriteArrayList<>();
    private final List<String> completed = new CopyOnWriteArrayList<>();

    @Override
    public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
        callbackNames.add("standDown");
        standDowns.add(new StandDownCall(reason, workflowId, detail));
    }

    @Override
    public void workflowFailed(WorkflowInfo w, String exceptionType) {
        callbackNames.add("workflowFailed");
        failed.add(w.workflowId());
    }

    @Override
    public void workflowCompensating(WorkflowInfo w) {
        callbackNames.add("workflowCompensating");
        compensating.add(w.workflowId());
    }

    @Override
    public void workflowCompleted(WorkflowInfo w) {
        callbackNames.add("workflowCompleted");
        completed.add(w.workflowId());
    }

    /** @return every recorded stand-down, in arrival order */
    public List<StandDownCall> standDowns() {
        return List.copyOf(standDowns);
    }

    /** @return the workflow IDs observed failing */
    public List<String> failed() {
        return List.copyOf(failed);
    }

    /** @return the workflow IDs observed entering compensation */
    public List<String> compensating() {
        return List.copyOf(compensating);
    }

    /** @return the workflow IDs observed completing */
    public List<String> completed() {
        return List.copyOf(completed);
    }

    /** @return the recorded callback names, for failure messages */
    public List<String> callbackNames() {
        return List.copyOf(callbackNames);
    }
}
