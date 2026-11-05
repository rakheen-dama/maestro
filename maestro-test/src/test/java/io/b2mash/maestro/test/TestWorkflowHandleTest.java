package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The handle's waiting and failure-inspection helpers. */
class TestWorkflowHandleTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void awaitStatusRejectsATerminalStatus() {
        var handle = env.startWorkflow(Parked.class, null);

        for (var terminal : new WorkflowStatus[]{
                WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.TERMINATED}) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> handle.awaitStatus(terminal, Duration.ofSeconds(1)));
            assertTrue(error.getMessage().contains("awaitTerminal"), error.getMessage());
        }
    }

    @Test
    void awaitStatusReturnsOnceTheStatusIsReached() throws Exception {
        var handle = env.startWorkflow(Parked.class, null);

        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));

        assertEquals(WorkflowStatus.WAITING_SIGNAL, handle.getStatus());
    }

    @Test
    void awaitStatusTimesOutNamingTheCurrentStatus() {
        var handle = env.startWorkflow(Parked.class, null);

        var error = assertThrows(TimeoutException.class,
                () -> handle.awaitStatus(WorkflowStatus.WAITING_TIMER, Duration.ofMillis(300)));
        assertTrue(error.getMessage().contains("WAITING_SIGNAL"), error.getMessage());
    }

    @Test
    void awaitStatusFailsFastWhenTheWorkflowEndsInstead() {
        var handle = env.startWorkflow(Failing.class, null);

        var error = assertThrows(IllegalStateException.class,
                () -> handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(10)));
        assertTrue(error.getMessage().contains("FAILED"), error.getMessage());
    }

    @Test
    void awaitTerminalReportsFailedWithoutThrowing_andGetFailureCarriesTheDetail() throws Exception {
        var handle = env.startWorkflow(Failing.class, null);

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(10)));

        var failure = handle.getFailure().orElseThrow();
        assertEquals(IllegalStateException.class.getName(), failure.exceptionType());
        assertEquals("boom", failure.message());
    }

    @Test
    void getFailureIsEmptyUnlessFailed() throws Exception {
        var handle = env.startWorkflow(Parked.class, null);
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
        assertTrue(handle.getFailure().isEmpty());

        handle.signal("go", "x");
        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(10)));
        assertTrue(handle.getFailure().isEmpty());
    }

    @Test
    void awaitStandDownTimesOutWhenThereIsNone() throws Exception {
        var handle = env.startWorkflow(Parked.class, null);
        handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));

        assertThrows(TimeoutException.class, () -> handle.awaitStandDown(Duration.ofMillis(200)));
        assertTrue(handle.standDowns().isEmpty());
        assertTrue(handle.liveSteps().isEmpty());
        assertTrue(handle.replayedSteps().isEmpty());
        assertEquals(0, handle.recoveries());
    }

    @DurableWorkflow(name = "handle-parked")
    public static class Parked {
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofHours(1));
        }
    }

    @DurableWorkflow(name = "handle-failing")
    public static class Failing {
        @WorkflowMethod
        public String run() {
            throw new IllegalStateException("boom");
        }
    }
}
