package io.b2mash.maestro.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the property that makes {@link MaestroControlFlowError} worth existing:
 * a workflow author's ordinary {@code catch (Exception e)} cannot intercept any
 * of the engine's control-flow signals, and a broad-catch site inside the
 * engine can recognise all of them with one type test.
 *
 * <p>The bug each signal exists to prevent is the same shape — a routine
 * operation (a deploy, an operator terminate, a mixed-version window) recorded
 * as a workflow <em>failure</em>, with compensations run for work that never
 * failed. Every one of those bugs is reinstated the instant one of these types
 * becomes catchable as an {@code Exception}, so this is a behavioural pin, not
 * a taxonomy assertion.
 */
@DisplayName("MaestroControlFlowError — the engine's control-flow signals sit outside catch (Exception)")
class MaestroControlFlowErrorTest {

    private static List<MaestroControlFlowError> signals() {
        return List.of(
                new ExecutorShutdownException("node stopping"),
                new WorkflowTerminatedException("wf-1", "operator asked"),
                new UnknownWorkflowHistoryException("wf-1", 7,
                        UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE, "newer node"));
    }

    @Test
    @DisplayName("a workflow author's catch (Exception) cannot swallow any of them")
    void catchExceptionCannotSwallowThem() {
        for (var signal : signals()) {
            var escaped = escapesWorkflowAuthorsCatchException(signal);
            assertSame(signal, escaped,
                    signal.getClass().getSimpleName() + " must escape catch (Exception) intact — "
                            + "swallowing it turns a routine operation into a recorded failure");
        }
    }

    @Test
    @DisplayName("every signal is an Error and not an Exception")
    void everySignalIsAnError() {
        for (var signal : signals()) {
            assertAll(signal.getClass().getSimpleName(),
                    () -> assertInstanceOf(Error.class, signal),
                    () -> assertFalse(Exception.class.isInstance(signal),
                            "must not be an Exception — catch (Exception) would reach it"),
                    () -> assertFalse(MaestroException.class.isInstance(signal),
                            "must not be a MaestroException — that hierarchy is workflow "
                                    + "failures, which these deliberately are not"));
        }
    }

    @Test
    @DisplayName("one instanceof check recognises all three — the base is what keeps broad-catch sites honest")
    void oneCheckRecognisesAllSignals() {
        for (var signal : signals()) {
            assertTrue(rethrowsControlFlow(signal),
                    "a broad catch (Throwable) that checks MaestroControlFlowError must rethrow "
                            + signal.getClass().getSimpleName() + " before recording a failure");
        }
        assertFalse(rethrowsControlFlow(new IllegalStateException("a genuine failure")),
                "an ordinary failure must NOT be mistaken for a control-flow signal");
    }

    @Test
    @DisplayName("the base is sealed to exactly the three signals — a fourth needs a deliberate edit")
    void sealedToExactlyThreeSubtypes() {
        var permitted = Stream.of(MaestroControlFlowError.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toSet());
        assertAll(
                () -> assertTrue(MaestroControlFlowError.class.isSealed(),
                        "sealing is what makes the §6.4 catch-site audit finite"),
                () -> assertEquals(
                        Set.of("ExecutorShutdownException", "WorkflowTerminatedException",
                                "UnknownWorkflowHistoryException"),
                        permitted));
    }

    @Test
    @DisplayName("UnknownWorkflowHistoryException carries the workflow, sequence and kind an operator needs")
    void unknownHistoryCarriesItsContext() {
        var e = new UnknownWorkflowHistoryException("order-42", 13,
                UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, "unreadable marker");
        assertAll(
                () -> assertEquals("order-42", e.workflowId()),
                () -> assertEquals(13, e.sequenceNumber()),
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_PAYLOAD, e.kind()),
                () -> assertEquals("unreadable marker", e.getMessage()));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * @return the signal if it escaped a workflow author's {@code catch
     *         (Exception)} intact, or {@code null} if that block swallowed it
     */
    private static @org.jspecify.annotations.Nullable Throwable escapesWorkflowAuthorsCatchException(
            MaestroControlFlowError signal) {
        try {
            workflowAuthorCode(signal);
            return null; // swallowed — the bug these types exist to prevent
        } catch (MaestroControlFlowError escaped) {
            return escaped;
        }
    }

    /** Ordinary, reasonable-looking workflow code around a park point. */
    private static void workflowAuthorCode(MaestroControlFlowError signal) {
        try {
            throw signal;
        } catch (Exception e) {
            // "log and continue" — must never see an engine control-flow signal
        }
    }

    /**
     * Models {@code SagaManager.executeParallel}'s outcome loop: a throwable
     * collected from a branch, checked before anything is recorded as a
     * failure.
     *
     * @return whether the collector rethrew instead of recording a failure
     */
    private static boolean rethrowsControlFlow(Throwable collected) {
        try {
            if (collected instanceof MaestroControlFlowError controlFlow) {
                throw controlFlow;
            }
            return false;
        } catch (MaestroControlFlowError rethrown) {
            return true;
        }
    }
}
