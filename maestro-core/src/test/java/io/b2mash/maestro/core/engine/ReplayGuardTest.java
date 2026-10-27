package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * C10: replay must compare the stored event's <em>step name</em> (and a
 * compatible type, and a fork's branch count) with the operation the current
 * code performs at that sequence — matching on sequence number alone lets a
 * renamed or reordered step silently replay another step's payload.
 */
@DisplayName("ReplayGuard — a stored event must match the step the code performs at its sequence")
class ReplayGuardTest {

    @Test
    void matchingTypeAndStep_returnsTheEvent() {
        var e = event(2, EventType.ACTIVITY_COMPLETED, "Acts.a");
        assertSame(e, ReplayGuard.requireMatch(e, "wf", "Acts.a",
                EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED));
    }

    @Test
    void differentStepName_standsDownAsNondeterministic() {
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                ReplayGuard.requireMatch(event(2, EventType.ACTIVITY_COMPLETED, "Acts.old"), "wf",
                        "Acts.new", EventType.ACTIVITY_COMPLETED));
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY, ex.kind()),
                () -> assertEquals(2, ex.sequenceNumber()),
                () -> assertEquals("seq=2 expected=Acts.new stored=Acts.old type=ACTIVITY_COMPLETED",
                        ex.detail()));
    }

    @Test
    void incompatibleType_standsDownEvenWhenStepNameMatches() {
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                ReplayGuard.requireMatch(event(2, EventType.SIDE_EFFECT, "$maestro:sleep"), "wf",
                        "$maestro:sleep", EventType.TIMER_SCHEDULED));
        assertEquals("seq=2 expected=$maestro:sleep stored=$maestro:sleep type=SIDE_EFFECT", ex.detail());
    }

    @Test
    void nullStoredStepName_standsDown() {   // a lifecycle event sitting in a replay slot
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                ReplayGuard.requireMatch(event(2, EventType.WORKFLOW_FAILED, null), "wf", "Acts.a",
                        EventType.ACTIVITY_COMPLETED));
        assertEquals("seq=2 expected=Acts.a stored=null type=WORKFLOW_FAILED", ex.detail());
    }

    @Test
    void branchCountMismatch_standsDownAsNondeterministic() {
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                ReplayGuard.requireBranchCount(event(2, EventType.SIDE_EFFECT, "$maestro:parallel",
                        "{\"branchCount\":3}"), "wf", 2));
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY, ex.kind()),
                () -> assertEquals("seq=2 expected=$maestro:parallel branches=2 stored=$maestro:parallel "
                        + "branches=3", ex.detail()));
    }

    @Test
    void branchCountUnreadable_isThePayloadKindNotDrift() {
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                ReplayGuard.requireBranchCount(event(2, EventType.SIDE_EFFECT, "$maestro:parallel",
                        "{\"reshaped\":true}"), "wf", 2));
        assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, ex.kind());
    }

    @Test
    void fourArgConstructor_keepsTheSeqOnlyDetail() {
        var ex = new UnknownWorkflowHistoryException("wf", 7,
                UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE, "m");
        assertEquals("seq=7", ex.detail());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static WorkflowEvent event(int seq, EventType type, @Nullable String stepName) {
        return new WorkflowEvent(UUID.randomUUID(), UUID.randomUUID(), seq, type, stepName, null,
                Instant.now());
    }

    private static WorkflowEvent event(int seq, EventType type, @Nullable String stepName,
                                       String payloadJson) {
        return new WorkflowEvent(UUID.randomUUID(), UUID.randomUUID(), seq, type, stepName,
                JsonMapper.builder().build().readTree(payloadJson), Instant.now());
    }
}
