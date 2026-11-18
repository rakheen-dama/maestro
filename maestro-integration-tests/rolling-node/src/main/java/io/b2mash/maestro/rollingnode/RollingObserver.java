package io.b2mash.maestro.rollingnode;

import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.core.observe.ActivityInfo;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.observe.WorkflowInfo;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reports the engine callbacks the rolling-upgrade test judges a node by, one
 * line each on standard output, so the test can tell a run that stood down from
 * one that is merely slow:
 *
 * <pre>ROLLING-EVENT|&lt;callback&gt;|&lt;workflowId&gt;|&lt;reason or type&gt;|&lt;detail&gt;</pre>
 *
 * <p>The starter collects every {@code EngineObserver} bean into the engine's
 * composite observer. A line is printed whole by one {@code println}, so lines
 * from concurrent runs never interleave.
 *
 * <p><b>Thread safety:</b> stateless.
 */
@Component
public class RollingObserver implements EngineObserver {

    @Override
    public void standDown(StandDownReason reason, String workflowId, @Nullable String detail) {
        emit("standDown", workflowId, reason.name(), detail);
    }

    @Override
    public void runAbandoned(WorkflowInfo w, AbandonReason reason) {
        emit("runAbandoned", w.workflowId(), reason.name(), null);
    }

    @Override
    public void workflowFailed(WorkflowInfo w, String exceptionType) {
        emit("workflowFailed", w.workflowId(), exceptionType, null);
    }

    @Override
    public void activityResultUnreadable(ActivityInfo a, String exceptionType) {
        emit("activityResultUnreadable", a.workflowId(), exceptionType, null);
    }

    private static void emit(String callback, String workflowId, String reason, @Nullable String detail) {
        System.out.println(line(callback, workflowId, reason, detail));
    }

    /**
     * The line for one callback: five {@code |}-separated fields, the detail
     * flattened to one line with any {@code |} replaced, so the test's parser
     * always sees exactly five fields.
     */
    static String line(String callback, String workflowId, String reason, @Nullable String detail) {
        return "ROLLING-EVENT|" + callback + "|" + workflowId + "|" + reason + "|"
                + (detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ').replace('|', '/'));
    }
}
