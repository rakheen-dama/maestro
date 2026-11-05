package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeterminismChecker#assertRecoverable}: crash after every completed
 * activity in turn, recover, and require the recovered run to finish exactly as
 * an uninterrupted one did.
 */
class DeterminismCheckerRecoverableTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Echo.class, new UpperEcho());
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void aDeterministicWorkflowRecoversFromEveryCrashPoint() {
        DeterminismChecker.assertRecoverable(env, Deterministic.class, "in");
    }

    @Test
    void aResultTypeThatCannotBeReadBack_isReportedAsAStandDown() {
        var error = assertThrows(AssertionError.class,
                () -> DeterminismChecker.assertRecoverable(env, Unreadable.class, "in"));

        assertTrue(error.getMessage().contains("UNKNOWN_EVENT_PAYLOAD"), error.getMessage());
        assertTrue(error.getMessage().contains("after 1 completed"), error.getMessage());
    }

    @Test
    void anArgumentThatDependsOnTheWorkflowObject_isReportedAsAStandDown() {
        var error = assertThrows(AssertionError.class,
                () -> DeterminismChecker.assertRecoverable(env, ObjectCounter.class, "in"));

        assertTrue(error.getMessage().contains("NONDETERMINISTIC_HISTORY"), error.getMessage());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public sealed interface Shape permits Square {
    }

    public record Square(int side) implements Shape {
    }

    @Activity
    public interface Echo {
        String echo(String in);

        Shape shape(String in);
    }

    static final class UpperEcho implements Echo {
        @Override
        public String echo(String in) {
            return in.toUpperCase();
        }

        @Override
        public Shape shape(String in) {
            return new Square(in.length());
        }
    }

    @DurableWorkflow(name = "recoverable-deterministic")
    public static class Deterministic {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Echo echo;

        @WorkflowMethod
        public String run(String in) {
            var a = echo.echo(in);
            var b = echo.echo(a + "-b");
            return echo.echo(b + "-c");
        }
    }

    /** Returns a sealed interface with no type information: fine live, unreadable on replay. */
    @DurableWorkflow(name = "recoverable-unreadable")
    public static class Unreadable {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Echo echo;

        @WorkflowMethod
        public String run(String in) {
            var shape = echo.shape(in);
            return echo.echo(shape.toString());
        }
    }

    /** Passes a per-object counter to an activity — a different value on every workflow object. */
    @DurableWorkflow(name = "recoverable-object-counter")
    public static class ObjectCounter {
        private static final AtomicInteger CONSTRUCTED = new AtomicInteger();

        private final int serial = CONSTRUCTED.incrementAndGet();

        @ActivityStub(startToCloseTimeout = "PT10S")
        private Echo echo;

        @WorkflowMethod
        public String run(String in) {
            var a = echo.echo(in + "#" + serial);
            return echo.echo(a);
        }
    }
}
