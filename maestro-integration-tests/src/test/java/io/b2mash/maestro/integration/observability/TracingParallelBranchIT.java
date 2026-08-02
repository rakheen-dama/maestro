package io.b2mash.maestro.integration.observability;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.OtelTracingFixture;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.ChainActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.spring.observe.TracingEngineObserver;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fix round 1, F2: a fan-out must not leave an un-{@code end()}ed run segment on
 * a parallel-branch thread.
 *
 * <p>Each branch of {@code WorkflowContext.parallel} runs on its own virtual
 * thread. Nothing ever tells the tracing adapter that a branch has finished, so
 * a segment opened on a branch thread could never be closed — the thread would
 * die with the span un-ended, the span would never be exported, and the branch's
 * activity spans would export naming a parent the backend never receives.
 *
 * <p>{@link TestWorkflows.ParallelWorkflow} is deliberately the harder shape:
 * it forks as its <em>first</em> statement, so the forking thread has not yet
 * opened a segment and there is no inherited fork point to hang the branches off.
 */
@Tag("integration")
@DisplayName("A parallel fan-out leaves no unclosed segment and no orphan parent")
class TracingParallelBranchIT extends PostgresIntegrationSupport {

    private static final Duration BOUND = Duration.ofSeconds(30);
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private static final String SEGMENT_SPAN = "maestro.workflow.run";
    private static final String ACTIVITY_SPAN = "maestro.activity";

    @Test
    @DisplayName("every exported span's local parent was itself exported — no branch span "
            + "references a segment that was never closed")
    void fanOutLeavesNoOrphanParent() throws Exception {
        var recorder = new CountingActivities.Recorder();

        try (var otel = new OtelTracingFixture("fanout-node")) {
            var node = MaestroEngineHarness.builder(store, objectMapper)
                    .serviceName("fanout-node")
                    .lock(newLock())
                    .instanceLockTtl(LOCK_TTL)
                    .observer(new TracingEngineObserver(otel.tracer(), otel.propagator()))
                    .build();
            node.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            node.registerWorkflow(new TestWorkflows.ParallelWorkflow());

            var workflowId = MaestroEngineHarness.uniqueWorkflowId("tracing-fanout");
            var handle = node.start(workflowId, TestWorkflows.ParallelWorkflow.class, "seed");
            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
            node.close();

            var spans = otel.finishedSpans();
            assertEquals(3, otel.spansNamed(ACTIVITY_SPAN).size(),
                    "one activity span per branch");

            // The load-bearing assertion: a span opened on a branch thread and
            // never closed would never be exported, so any child naming it would
            // be an orphan. Every LOCAL parent must therefore be present here.
            Set<String> exportedIds = spans.stream().map(SpanData::getSpanId).collect(Collectors.toSet());
            var orphans = spans.stream()
                    .filter(s -> s.getParentSpanContext().isValid())
                    .filter(s -> !s.getParentSpanContext().isRemote())
                    .filter(s -> !exportedIds.contains(s.getParentSpanId()))
                    .toList();
            assertTrue(orphans.isEmpty(),
                    () -> "these spans name a local parent that was never exported — the parent "
                            + "segment was opened on a branch thread and never closed: "
                            + orphans.stream()
                            .map(s -> s.getName() + "(parent=" + s.getParentSpanId() + ")").toList());

            // And nothing dangles: a segment opened per branch would show up as
            // extra, unclosed segments. This workflow forks immediately, so its
            // main thread legitimately never opens one either.
            assertTrue(otel.spansNamed(SEGMENT_SPAN).size() <= 1,
                    () -> "a fan-out must not mint one run segment per branch; got "
                            + otel.spansNamed(SEGMENT_SPAN).size());
            assertEquals(3, recorder.invocations().size(),
                    "sanity check on the fixture: all three branches really ran");
        }
    }

    /**
     * Fix round 2: the F2 residual. The branch predicate used to be consulted
     * only from {@code activityStarted}, so a branch that then <em>parks</em> —
     * explicitly supported; a branch may {@code sleep()} or {@code awaitSignal()}
     * — reached {@code ensureSegment} again through {@code workflowUnparked} /
     * {@code timerFired}, which did not consult it, and opened a segment on the
     * branch thread that nothing could ever close. Gating {@code ensureSegment}
     * itself covers all four entry points.
     */
    @Test
    @DisplayName("a fork-first workflow whose branches park still leaves no unclosed segment "
            + "and no orphan parent")
    void parkingBranchesLeaveNoOrphanParent() throws Exception {
        var recorder = new CountingActivities.Recorder();

        try (var otel = new OtelTracingFixture("parking-fanout-node")) {
            var node = MaestroEngineHarness.builder(store, objectMapper)
                    .serviceName("parking-fanout-node")
                    .lock(newLock())
                    .instanceLockTtl(LOCK_TTL)
                    .observer(new TracingEngineObserver(otel.tracer(), otel.propagator()))
                    .build();
            node.registerActivities(ChainActivities.class,
                    new CountingActivities.RecordingChainActivities(recorder));
            node.registerWorkflow(new ParkingBranchWorkflow());
            node.startTimerPoller(Duration.ofMillis(100), 20);

            var workflowId = MaestroEngineHarness.uniqueWorkflowId("tracing-fanout-park");
            var handle = node.start(workflowId, ParkingBranchWorkflow.class, "seed");
            assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(BOUND));
            node.close();

            var spans = otel.finishedSpans();
            Set<String> exportedIds = spans.stream().map(SpanData::getSpanId).collect(Collectors.toSet());
            var orphans = spans.stream()
                    .filter(sp -> sp.getParentSpanContext().isValid())
                    .filter(sp -> !sp.getParentSpanContext().isRemote())
                    .filter(sp -> !exportedIds.contains(sp.getParentSpanId()))
                    .toList();
            assertTrue(orphans.isEmpty(),
                    () -> "a parking branch opened a segment nothing closed; orphaned spans: "
                            + orphans.stream()
                            .map(sp -> sp.getName() + "(parent=" + sp.getParentSpanId() + ")").toList());
            assertTrue(otel.spansNamed(SEGMENT_SPAN).size() <= 1,
                    () -> "a parking branch must not mint its own run segment; got "
                            + otel.spansNamed(SEGMENT_SPAN).size());
        }
    }

    /**
     * Forks as its first statement, and the branch runs an activity and then
     * parks on a timer — the shape that exercises {@code ensureSegment}'s
     * non-{@code activityStarted} entry points ({@code workflowUnparked},
     * {@code timerFired}) on a branch thread.
     */
    @DurableWorkflow(name = "ParkingBranchWorkflow")
    public static class ParkingBranchWorkflow {

        @ActivityStub
        ChainActivities activities;

        /**
         * @param input the seed value
         * @return the branch results, joined
         */
        @WorkflowMethod
        public String run(String input) {
            // Exactly two branches, of which only ONE touches the store.
            //
            // Two is the minimum that actually forks: parallel() runs a
            // single-task list inline on the calling thread, so a one-branch
            // fixture would not exercise a branch thread at all. And only one
            // branch may park, because two branches parking at once race each
            // other on the instance status row and the run dies with an
            // OptimisticLockException — an engine-level limitation of concurrent
            // parking branches, entirely separate from tracing.
            List<Callable<String>> branches = List.of(
                    () -> {
                        activities.stepOne(input);
                        WorkflowContext.current().sleep(Duration.ofMillis(50));
                        // The second activity is what makes the defect visible:
                        // if the wake opened a segment on this branch thread,
                        // this span becomes its child — and that segment is
                        // never closed, so it is never exported and this span
                        // is left naming a parent nobody ever sees.
                        return activities.stepThree(input);
                    },
                    () -> "noop");
            return String.join(",", WorkflowContext.current().parallel(branches));
        }
    }
}
