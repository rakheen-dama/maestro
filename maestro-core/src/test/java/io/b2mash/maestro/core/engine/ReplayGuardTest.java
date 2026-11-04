package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void requireAbsent_emptySlotPasses() {
        assertDoesNotThrow(() -> ReplayGuard.requireAbsent(Optional.empty(), "wf",
                "$maestro:sequential-compensation"));
    }

    @Test
    void requireAbsent_anyPresentEventStandsDownAsNondeterministic() {
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                ReplayGuard.requireAbsent(Optional.of(event(7, EventType.SIDE_EFFECT,
                        "$maestro:parallel-compensation")), "wf", "$maestro:sequential-compensation"));
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY, ex.kind()),
                () -> assertEquals(7, ex.sequenceNumber()),
                () -> assertEquals("seq=7 expected=$maestro:sequential-compensation "
                        + "stored=$maestro:parallel-compensation type=SIDE_EFFECT", ex.detail()));
    }

    @Test
    void driftMessage_carriesNoRemedy_theWarnAddsExactlyOne() {
        // The executor's WARN appends standDownRemedy(NONDETERMINISTIC_HISTORY);
        // a remedy in the message too printed "(roll back the deploy) — ...;
        // roll back the deploy or gate the change behind workflow.version()".
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                ReplayGuard.requireMatch(event(2, EventType.ACTIVITY_COMPLETED, "Acts.old"), "wf",
                        "Acts.new", EventType.ACTIVITY_COMPLETED));
        var warn = ex.getMessage() + "; " + WorkflowExecutor.standDownRemedy(ex.kind());
        assertEquals(1, warn.split("roll back", -1).length - 1, warn);
    }

    // ── requireArgumentsMatch ─────────────────────────────────────────

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void sameArgsHash_returnsTheEvent() {
        var e = eventWithArgsHash(3, HASH_A);
        assertSame(e, ReplayGuard.requireArgumentsMatch(e, "wf", "Acts.a", HASH_A));
    }

    @Test
    @DisplayName("a stored row without a hash (older node, pre-V7 row) is unverified, not a mismatch")
    void nullStoredHash_isAccepted() {
        var e = eventWithArgsHash(3, null);
        assertSame(e, ReplayGuard.requireArgumentsMatch(e, "wf", "Acts.a", HASH_A));
    }

    @Test
    @DisplayName("arguments that could not be fingerprinted are unverified, not a mismatch")
    void nullComputedHash_isAccepted() {
        var e = eventWithArgsHash(3, HASH_A);
        assertSame(e, ReplayGuard.requireArgumentsMatch(e, "wf", "Acts.a", null));
    }

    @Test
    void differentArgsHash_standsDownAsNondeterministic_quotingEightHexOfEach() {
        var ex = assertThrows(UnknownWorkflowHistoryException.class, () ->
                ReplayGuard.requireArgumentsMatch(eventWithArgsHash(3, HASH_B), "wf", "Acts.a", HASH_A));
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.NONDETERMINISTIC_HISTORY, ex.kind()),
                () -> assertEquals("wf", ex.workflowId()),
                () -> assertEquals(3, ex.sequenceNumber()),
                () -> assertEquals("seq=3 step=Acts.a argsHash expected=aaaaaaaa stored=bbbbbbbb", ex.detail()),
                () -> assertTrue(ex.getMessage().contains("activity 'Acts.a' is being called with different "
                        + "arguments than the recorded run"), ex.getMessage()),
                () -> assertTrue(ex.getMessage().contains("non-deterministic input"), ex.getMessage()),
                // R17: the detail quotes hashes only, never the full value or the argument itself.
                () -> assertEquals(-1, ex.detail().indexOf(HASH_A)),
                () -> assertEquals(-1, ex.detail().indexOf(HASH_B)));
    }

    private static WorkflowEvent eventWithArgsHash(int seq, @Nullable String argsHash) {
        return new WorkflowEvent(UUID.randomUUID(), UUID.randomUUID(), seq, EventType.ACTIVITY_COMPLETED,
                "Acts.a", null, Instant.now(), argsHash);
    }

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
