package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.engine.RetryPlan.Basis;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static io.b2mash.maestro.core.model.EventType.ACTIVITY_COMPLETED;
import static io.b2mash.maestro.core.model.EventType.ACTIVITY_FAILED;
import static io.b2mash.maestro.core.model.EventType.SIDE_EFFECT;
import static io.b2mash.maestro.core.model.EventType.SIGNAL_RECEIVED;
import static io.b2mash.maestro.core.model.EventType.SIGNAL_TIMEOUT;
import static io.b2mash.maestro.core.model.EventType.TIMER_FIRED;
import static io.b2mash.maestro.core.model.EventType.TIMER_SCHEDULED;
import static io.b2mash.maestro.core.model.EventType.WORKFLOW_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP3 Task 3 — {@link RetryPlanner#plan} computes exactly what an admin Retry
 * deletes (D-3): the attributed failure and its propagation path (3.3, D10,
 * Issue 33), last-in-lane when nothing is attributed, today's plan when the
 * lane walk leaves an event unplaced, and a refusal when the deleted region
 * holds a consumed signal (MD-1).
 */
@DisplayName("RetryPlanner computes what a Retry deletes")
class RetryPlannerTest {

    private static final UUID INSTANCE = UUID.randomUUID();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String AEE = "io.b2mash.maestro.core.exception.ActivityExecutionException";
    private static final String STE = "io.b2mash.maestro.core.exception.SignalTimeoutException";
    private static final String ISE = "java.lang.IllegalStateException";
    private static final String AWAIT = "$maestro:awaitSignal:";

    @Test
    void auditB33_deletesOnlyTheFinishFailure() {
        var plan = plan(af(1, "primary", ISE, "down"), ac(2, "fallback"), af(3, "finish", ISE, "boom"),
                wf(4, "[3]", AEE, "x"));
        assertPlan(Basis.ATTRIBUTED, Set.of(3, 4), plan);
    }

    @Test
    void d10_truncatesTheAlertWithTheCaughtFailure() {
        var plan = plan(af(1, "primary", ISE, "down"), ac(2, "alert"), wf(3, "[1]", AEE, "x"));
        assertPlan(Basis.ATTRIBUTED, Set.of(1, 2, 3), plan);
    }

    @Test
    void d10InABranch_truncatesTheRootTailAndKeepsTheSibling() {
        var plan = plan(fork(1, 2), af(2001, "a", ISE, "down"), ac(2002, "alert"), ac(3001, "b1"), ac(3002, "b2"),
                ac(4001, "rootAlert"), wf(4002, "[2001]", AEE, "x"));
        assertPlan(Basis.ATTRIBUTED, Set.of(2001, 2002, 4001, 4002), plan);
    }

    @Test
    void twoFailedBranches_deleteOnlyTheAttributedOne() {
        var plan = plan(fork(1, 2), af(2001, "a", ISE, "down"), af(3001, "b", ISE, "down"),
                wf(4001, "[2001]", AEE, "x"));
        assertPlan(Basis.ATTRIBUTED, Set.of(2001, 4001), plan);
    }

    @Test
    void aParkedAwaitTimeout_deletesItsParkMemoAndRow() {
        var plan = plan(awaitPark(1, "go"), ev(2, SIGNAL_TIMEOUT, AWAIT + "go", null), wf(3, "[2]", STE, "x"));
        assertPlan(Basis.ATTRIBUTED, Set.of(1, 2, 3), plan);
        assertTrue(plan.deleteTimerIds().containsAll(Set.of("await-signal-1")), plan.deleteTimerIds()::toString);
        assertEquals(6, plan.deleteTimerIds().size(), plan.deleteTimerIds()::toString);
    }

    @Test
    void issue33Shape1_clearsOnlyTheMainAwait() {
        var plan = plan(fork(1, 2), fork(2001, 2), ac(2002001, "x"), ac(2003001, "y"),
                awaitPark(2004001, "gate"), ev(2004002, SIGNAL_TIMEOUT, AWAIT + "gate", null), ac(3001, "z"),
                awaitPark(4001, "main"), ev(4002, SIGNAL_TIMEOUT, AWAIT + "main", null), wf(4003, "[4002]", STE, "x"));
        assertPlan(Basis.ATTRIBUTED, Set.of(4001, 4002, 4003), plan);
    }

    @Test
    void issue33Shape2_clearsOnlyTheLowerBranch() {
        var plan = plan(fork(1, 2), awaitPark(2001, "a"), ev(2002, SIGNAL_TIMEOUT, AWAIT + "a", null),
                awaitPark(3001, "gate"), ev(3002, SIGNAL_TIMEOUT, AWAIT + "gate", null), wf(4001, "[2002]", STE, "x"));
        assertPlan(Basis.ATTRIBUTED, Set.of(2001, 2002, 4001), plan);
    }

    @Test
    void aSleepInTheTruncatedRegion_deletesItsRow() {
        var plan = plan(af(1, "p", ISE, "down"),
                ev(2, TIMER_SCHEDULED, "$maestro:sleep", "{\"timerId\":\"sleep-2\",\"duration\":\"PT1S\"}"),
                ev(3, TIMER_FIRED, "$maestro:sleep", null), wf(4, "[1]", AEE, "x"));
        assertPlan(Basis.ATTRIBUTED, Set.of(1, 2, 3, 4), plan);
        assertTrue(plan.deleteTimerIds().containsAll(Set.of("sleep-2")), plan.deleteTimerIds()::toString);
        assertEquals(8, plan.deleteTimerIds().size(), plan.deleteTimerIds()::toString);
    }

    @Test
    void aConsumedSignalInTheTruncatedRegion_refuses() {
        var plan = plan(af(1, "p", ISE, "down"), ev(2, SIGNAL_RECEIVED, AWAIT + "ack", "\"ok\""),
                wf(3, "[1]", AEE, "x"));
        assertTrue(plan.refused(), plan::toString);
        assertEquals(2, plan.consumedSignalAt());
    }

    @Test
    void aLegacyPayload_isMatchedByItsMessage() {
        // seed-alert-failed's rows exactly as cbf5a2a recorded them (seeded-history.sql:277-279)
        var history = List.of(
                ev(1, ACTIVITY_FAILED, "seed-failing.explode", "{\"message\": \"exploded on seed-alert-failed\", "
                        + "\"exceptionType\": \"io.b2mash.maestro.integration.upgrade.cbf5a2a."
                        + "SeededHistoryWorkflows$SeedFailure\"}"),
                ev(2, ACTIVITY_COMPLETED, "seed-failing.alert", "\"alerted:seed-alert-failed\""),
                ev(3, WORKFLOW_FAILED, null, "{\"message\": \"Activity 'seed-failing.explode' failed in workflow "
                        + "'seed-alert-failed': exploded on seed-alert-failed\", \"exceptionType\": "
                        + "\"io.b2mash.maestro.core.exception.ActivityExecutionException\"}"));
        assertPlan(Basis.MATCHED_MESSAGE, Set.of(1, 2, 3), RetryPlanner.plan("seed-alert-failed", history));
    }

    @Test
    void aLegacyPayloadMatchingTwoMemos_fallsToLastInLane() {
        var plan = plan(af(1, "p", ISE, "down"), af(2, "p", ISE, "down"),
                wf(3, null, AEE, "Activity 'p' failed in workflow 'w': down"));
        assertPlan(Basis.LAST_IN_LANE, Set.of(2, 3), plan);
    }

    @Test
    void aWrapWithoutCause_deletesOnlyALastInLaneFailure() {
        var plan = plan(af(1, "p", ISE, "down"), ac(2, "fallback"), af(3, "finish", ISE, "boom"),
                wf(4, "[]", ISE, "x"));
        assertPlan(Basis.LAST_IN_LANE, Set.of(3, 4), plan);
    }

    @Test
    void aCaughtLastOperationOfABranchWhoseValueTheRootUsed_isKept() {
        var plan = plan(fork(1, 2), af(2001, "a", ISE, "down"), ac(3001, "b"), ac(4001, "useA"),
                wf(4002, "[]", ISE, "x"));
        assertPlan(Basis.LAST_IN_LANE, Set.of(4002), plan);
    }

    @Test
    void anUnplacedEvent_fallsBackToTheLegacyPlan() {
        var plan = plan(af(1, "p", ISE, "down"), ac(3, "fallback"), af(4, "finish", ISE, "boom"),
                wf(5, "[4]", AEE, "x"));
        assertPlan(Basis.LEGACY, Set.of(1, 4, 5), plan);
    }

    @Test
    void theLegacyPlanKeepsIssue19sTimeoutRule() {
        // slot 3 is empty, so 4 and 5 are unplaced
        var plan = plan(awaitPark(1, "a"), ev(2, SIGNAL_TIMEOUT, AWAIT + "a", null),
                awaitPark(4, "b"), ev(5, SIGNAL_TIMEOUT, AWAIT + "b", null),
                wf(6, null, STE, "Signal 'b' timed out after PT1S for workflow 'w'"));
        assertPlan(Basis.LEGACY, Set.of(4, 5, 6), plan);
        assertTrue(plan.deleteTimerIds().containsAll(Set.of("await-signal-4")), plan.deleteTimerIds()::toString);
        assertEquals(6, plan.deleteTimerIds().size(), plan.deleteTimerIds()::toString);
    }

    @Test
    void noWorkflowFailed_deletesNothing() {
        var plan = plan(ac(1, "a"), ac(2, "b"));
        assertPlan(Basis.LAST_IN_LANE, Set.of(), plan);
    }

    @Test
    void anAttributedSequenceThatIsNotAFailure_isIgnored() {
        var plan = plan(ac(1, "a"), af(2, "b", ISE, "down"), wf(3, "[1]", AEE, "x"));
        assertPlan(Basis.LAST_IN_LANE, Set.of(2, 3), plan);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static RetryPlan plan(WorkflowEvent... history) {
        return RetryPlanner.plan("w", List.of(history));
    }

    private static void assertPlan(Basis basis, Set<Integer> deletes, RetryPlan plan) {
        assertEquals(basis, plan.basis(), plan::toString);
        assertEquals(new TreeSet<>(deletes), plan.deleteSequences(), plan::toString);
        assertFalse(plan.refused(), plan::toString);
    }

    private static WorkflowEvent ev(int seq, EventType type, @Nullable String step, @Nullable String payloadJson) {
        return new WorkflowEvent(UUID.randomUUID(), INSTANCE, seq, type, step,
                payloadJson == null ? null : MAPPER.readTree(payloadJson), Instant.EPOCH);
    }

    private static WorkflowEvent ac(int seq, String step) {
        return ev(seq, ACTIVITY_COMPLETED, step, "\"done\"");
    }

    /** An {@code ACTIVITY_FAILED} memo as {@code ActivityInvocationHandler} writes it. */
    private static WorkflowEvent af(int seq, String step, String type, String msg) {
        var payload = MAPPER.createObjectNode().put("exceptionType", type).put("message", msg);
        return new WorkflowEvent(UUID.randomUUID(), INSTANCE, seq, ACTIVITY_FAILED, step, payload, Instant.EPOCH);
    }

    /** A {@code WORKFLOW_FAILED} payload; {@code failedSequencesJsonOrNull} {@code null} = a pre-WP3 build. */
    private static WorkflowEvent wf(int seq, @Nullable String failedSequencesJsonOrNull, String exceptionType,
                                    String message) {
        ObjectNode payload = MAPPER.createObjectNode().put("exceptionType", exceptionType).put("message", message);
        if (failedSequencesJsonOrNull != null) {
            payload.set("failedSequences", MAPPER.readTree(failedSequencesJsonOrNull));
        }
        return new WorkflowEvent(UUID.randomUUID(), INSTANCE, seq, WORKFLOW_FAILED, null, payload, Instant.EPOCH);
    }

    /** A parked await's {@code TIMER_SCHEDULED} memo, as {@code SignalManager} writes it. */
    private static WorkflowEvent awaitPark(int seq, String signal) {
        return ev(seq, TIMER_SCHEDULED, AWAIT + signal, "{\"signalName\":\"" + signal + "\",\"timerId\":\"await-signal-"
                + seq + "\",\"timeout\":\"PT1S\",\"deadline\":\"2026-11-17T00:00:00Z\"}");
    }

    private static WorkflowEvent fork(int seq, int n) {
        return ev(seq, SIDE_EFFECT, "$maestro:parallel", "{\"branchCount\":" + n + "}");
    }
}
