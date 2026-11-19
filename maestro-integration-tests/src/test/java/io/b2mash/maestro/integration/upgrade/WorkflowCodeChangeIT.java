package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The versioning playbook ({@code docs/operations.md} §10.2, §10.4, §10.7;
 * {@code docs/pitfalls.md} rule 7), run against the real store: a run parked
 * under the old workflow code is adopted by a node running the edited code.
 *
 * <p>Each case is two classes under one {@code @DurableWorkflow} name: the old
 * code (on node A, which starts the run, lets it park on signal {@code go},
 * and shuts down) and the edited code (on node B, which stores the {@code go}
 * signal, then adopts the run through recovery). Unless a case says otherwise,
 * the old code is {@link Base}: {@code a}, {@code b}, park on {@code go},
 * {@code c}.
 *
 * <ul>
 *   <li><b>A change the playbook calls safe</b> ({@link Safe}): the edited code
 *       resumes the run and completes it; the steps the old code recorded
 *       replay from the log (none runs again), the rest run once, the history
 *       the old code wrote is an unchanged prefix of the final one, and nothing
 *       stands down.</li>
 *   <li><b>A change it calls unsafe</b> ({@link Unsafe}): the run stands down
 *       with the reason and the detail the playbook documents. It writes
 *       nothing: the status is still {@code WAITING_SIGNAL}, the history is
 *       unchanged, no event or callback records a failure or a compensation,
 *       no activity runs again, and the {@code go} signal stays stored,
 *       unconsumed. Then the old code is redeployed (node C: the playbook's
 *       first remedy) and completes the run, consuming that same signal.</li>
 *   <li><b>Raising {@code minSupported} too early</b> is the one unsafe change
 *       the playbook says <em>fails</em> the run
 *       ({@code UnsupportedWorkflowVersionException}, §10.2). For a parked
 *       run the engine stands it down as {@code stale_run} instead
 *       ({@code docs/open-issues.md} Issue 89):
 *       {@link Unsafe#raisingTheFloorAboveARecordedVersionStandsDownToday}
 *       pins that, and the documented outcome is the disabled
 *       {@link Unsafe#raisingTheFloorAboveARecordedVersionFailsTheRunAsDocumented}.</li>
 * </ul>
 *
 * <p>A compensating saga is the one state that is not parked on a signal: its
 * cases ({@link Unsafe#aRenamedCompensationStandsDownACompensatingSaga},
 * {@link Unsafe#aFlippedParallelCompensationStandsDownACompensatingSaga})
 * hold node A inside its second compensation instead, as
 * {@code ReplayDriftStandDownIT} does.
 */
@Tag("integration")
@DisplayName("A run parked under old workflow code, adopted by edited code (versioning playbook)")
class WorkflowCodeChangeIT extends PostgresIntegrationSupport {

    private static final String SERVICE = "code-change";
    private static final String TYPE = "code-change";
    private static final String SIGNAL = "go";
    private static final Duration BOUND = Duration.ofSeconds(30);

    // ── Activities ─────────────────────────────────────────────────────────

    /** The steps the workflows call; every call is recorded as {@code "<method>:<key>"}. */
    @Activity(name = "change")
    public interface Steps {
        String a(String key);

        String b(String key);

        String c(String key);

        String inserted(String key);

        String replacement(String key);

        String renamedA(String key);
    }

    /** {@code Steps} under its interface name ({@code PaymentSteps}), as the old code declares it. */
    public interface PaymentSteps {
        String charge(String key);

        String capture(String key);
    }

    /** The same steps after the interface was renamed, its recorded group pinned: the playbook's remedy. */
    @Activity(name = "PaymentSteps")
    public interface BillingSteps {
        String charge(String key);

        String capture(String key);
    }

    /** A step whose result the old code declares as text. */
    @Activity(name = "change-quote")
    public interface QuoteAsText {
        String quote(String key);
    }

    /** The same step after its declared result type changed to a number: an incompatible payload change. */
    @Activity(name = "change-quote")
    public interface QuoteAsNumber {
        int quote(String key);
    }

    /** Saga steps: {@code reserve} is undone by {@code release}, {@code charge} by {@code refund}; {@code ship} fails. */
    @Activity(name = "change-saga")
    public interface SagaSteps {
        @Compensate("release")
        String reserve(String key);

        void release(String key);

        @Compensate("refund")
        String charge(String key);

        void refund(String key);

        String ship(String key);
    }

    /** The saga steps after {@code charge}'s compensation was renamed to {@code refundPayment}. */
    @Activity(name = "change-saga")
    public interface RenamedRefundSagaSteps {
        @Compensate("release")
        String reserve(String key);

        void release(String key);

        @Compensate("refundPayment")
        String charge(String key);

        void refundPayment(String key);

        String ship(String key);
    }

    /** Every activity interface above, recording each call. */
    static final class Recorder implements Steps, PaymentSteps, BillingSteps, QuoteAsText, SagaSteps,
            RenamedRefundSagaSteps {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile @Nullable CountDownLatch enteredRelease;
        volatile @Nullable CountDownLatch wedgeRelease;

        private String record(String method, String key) {
            calls.add(method + ":" + key);
            return method + ":" + key;
        }

        @Override public String a(String key) { return record("a", key); }
        @Override public String b(String key) { return record("b", key); }
        @Override public String c(String key) { return record("c", key); }
        @Override public String inserted(String key) { return record("inserted", key); }
        @Override public String replacement(String key) { return record("replacement", key); }
        @Override public String renamedA(String key) { return record("renamedA", key); }
        @Override public String charge(String key) { return record("charge", key); }
        @Override public String capture(String key) { return record("capture", key); }
        @Override public String quote(String key) { return record("quote", key); }
        @Override public String reserve(String key) { return record("reserve", key); }
        @Override public void refund(String key) { record("refund", key); }
        @Override public void refundPayment(String key) { record("refundPayment", key); }

        @Override
        public void release(String key) {
            record("release", key);
            var entered = enteredRelease;
            var wedge = wedgeRelease;
            if (entered != null && wedge != null) {
                entered.countDown();
                try {
                    if (!wedge.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the wedge was never opened");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while wedged", e);
                }
            }
        }

        @Override
        public String ship(String key) {
            record("ship", key);
            throw new IllegalStateException("ship refused for " + key);
        }
    }

    // ── The old code ───────────────────────────────────────────────────────

    /** {@code a}, {@code b}, park on {@code go}, {@code c}: the old code of most cases. */
    @DurableWorkflow(name = TYPE)
    public static class Base {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var a = steps.a(key);
            var b = steps.b(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** As {@link Base}, with {@code b}'s path chosen by {@code version("path", DEFAULT, 1)}. */
    @DurableWorkflow(name = TYPE)
    public static class VersionedV1 {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var ctx = WorkflowContext.current();
            var a = steps.a(key);
            int v = ctx.version("path", WorkflowContext.DEFAULT_VERSION, 1);
            var b = v >= 1 ? steps.b(key) : steps.replacement(key);
            var go = ctx.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key) + "|v" + v;
        }
    }

    /** {@code PaymentSteps.charge}, park on {@code go}, {@code PaymentSteps.capture}. */
    @DurableWorkflow(name = TYPE)
    public static class PaymentV1 {
        @ActivityStub
        PaymentSteps payment;

        @WorkflowMethod
        public String run(String key) {
            var charged = payment.charge(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return charged + "|" + go + "|" + payment.capture(key);
        }
    }

    /** Two parallel {@code a} branches, park on {@code go}, {@code c}. */
    @DurableWorkflow(name = TYPE)
    public static class ParallelV1 {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var ctx = WorkflowContext.current();
            var branches = ctx.parallel(List.<java.util.concurrent.Callable<String>>of(
                    () -> steps.a(key + "-1"), () -> steps.a(key + "-2")));
            var go = ctx.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return branches + "|" + go + "|" + steps.c(key);
        }
    }

    /** {@code quote} declared as text, park on {@code go}. */
    @DurableWorkflow(name = TYPE)
    public static class QuoteTextV1 {
        @ActivityStub
        QuoteAsText quotes;

        @WorkflowMethod
        public String run(String key) {
            var quote = quotes.quote(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return quote + "|" + go;
        }
    }

    /** A saga: reserve, charge, ship (which fails), compensated sequentially in LIFO order. */
    @DurableWorkflow(name = TYPE)
    public static class SagaV1 {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        SagaSteps saga;

        @Saga
        @WorkflowMethod
        public String run(String key) {
            saga.reserve(key);
            saga.charge(key);
            return saga.ship(key);
        }
    }

    // ── Edited code the playbook calls safe ────────────────────────────────

    /** A step inserted before the park, behind a {@code version()} gate (pitfalls rule 7, "Right"). */
    @DurableWorkflow(name = TYPE)
    public static class GatedInsertion {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var ctx = WorkflowContext.current();
            var a = steps.a(key);
            if (ctx.version("insert-step", WorkflowContext.DEFAULT_VERSION, 1) >= 1) {
                steps.inserted(key);
            }
            var b = steps.b(key);
            var go = ctx.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** A step replaced before the park, the old one kept for histories that predate the gate (concepts.md). */
    @DurableWorkflow(name = TYPE)
    public static class GatedReplacement {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var ctx = WorkflowContext.current();
            var a = steps.a(key);
            int v = ctx.version("replace-b", WorkflowContext.DEFAULT_VERSION, 1);
            var b = v == WorkflowContext.DEFAULT_VERSION ? steps.b(key) : steps.replacement(key);
            var go = ctx.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** {@link VersionedV1} with {@code maxSupported} raised to 2: a recorded 1 keeps its path (§10.2). */
    @DurableWorkflow(name = TYPE)
    public static class RaisedMaxSupported {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var ctx = WorkflowContext.current();
            var a = steps.a(key);
            int v = ctx.version("path", WorkflowContext.DEFAULT_VERSION, 2);
            var b = v >= 2 ? steps.inserted(key) : v >= 1 ? steps.b(key) : steps.replacement(key);
            var go = ctx.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key) + "|v" + v;
        }
    }

    /** {@link PaymentV1} with the interface renamed to {@code BillingSteps}, its group pinned (§10.7). */
    @DurableWorkflow(name = TYPE)
    public static class RenamedInterfacePinned {
        @ActivityStub
        BillingSteps billing;

        @WorkflowMethod
        public String run(String key) {
            var charged = billing.charge(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return charged + "|" + go + "|" + billing.capture(key);
        }
    }

    // ── Edited code the playbook calls unsafe ──────────────────────────────

    /** {@code a} renamed to {@code renamedA}, ungated. */
    @DurableWorkflow(name = TYPE)
    public static class RenamedActivity {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var a = steps.renamedA(key);
            var b = steps.b(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** {@code a} and {@code b} swapped, ungated. */
    @DurableWorkflow(name = TYPE)
    public static class Reordered {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var b = steps.b(key);
            var a = steps.a(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** A step inserted before the park, ungated (pitfalls rule 7, "Wrong"). */
    @DurableWorkflow(name = TYPE)
    public static class UngatedInsertion {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var a = steps.a(key);
            steps.inserted(key);
            var b = steps.b(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** {@code b} removed, ungated. */
    @DurableWorkflow(name = TYPE)
    public static class Removed {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var a = steps.a(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + go + "|" + steps.c(key);
        }
    }

    /** The awaited signal renamed from {@code go} to {@code go-now}. */
    @DurableWorkflow(name = TYPE)
    public static class RenamedSignal {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var a = steps.a(key);
            var b = steps.b(key);
            var go = WorkflowContext.current().awaitSignal("go-now", String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** A {@code sleep()} where the old code ran {@code b}. */
    @DurableWorkflow(name = TYPE)
    public static class SleepWhereAnActivityRan {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var ctx = WorkflowContext.current();
            var a = steps.a(key);
            ctx.sleep(Duration.ofMillis(1));
            var go = ctx.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + go + "|" + steps.c(key);
        }
    }

    /** {@link ParallelV1} forking three branches instead of two. */
    @DurableWorkflow(name = TYPE)
    public static class ParallelArityChanged {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var ctx = WorkflowContext.current();
            var branches = ctx.parallel(List.<java.util.concurrent.Callable<String>>of(
                    () -> steps.a(key + "-1"), () -> steps.a(key + "-2"), () -> steps.a(key + "-3")));
            var go = ctx.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return branches + "|" + go + "|" + steps.c(key);
        }
    }

    /** {@code a}'s argument built differently, ungated. */
    @DurableWorkflow(name = TYPE)
    public static class ChangedArgument {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var a = steps.a(key + "!");
            var b = steps.b(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** {@link QuoteTextV1} with {@code quote}'s result now declared as a number. */
    @DurableWorkflow(name = TYPE)
    public static class QuoteNumber {
        @ActivityStub
        QuoteAsNumber quotes;

        @WorkflowMethod
        public String run(String key) {
            int quote = quotes.quote(key);
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return quote + "|" + go;
        }
    }

    /** The new input shape of {@link ChangedInputShape}. */
    public record OrderInput(String sku, int quantity) {
    }

    /** {@link Base} with the workflow's input changed from a string to a record. */
    @DurableWorkflow(name = TYPE)
    public static class ChangedInputShape {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(OrderInput input) {
            var a = steps.a(input.sku());
            var b = steps.b(input.sku());
            var go = WorkflowContext.current().awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(input.sku());
        }
    }

    /** {@link VersionedV1} with the floor raised to 2 while a run recorded 1 (§10.2: fails, by design). */
    @DurableWorkflow(name = TYPE)
    public static class RaisedFloor {
        @ActivityStub
        Steps steps;

        @WorkflowMethod
        public String run(String key) {
            var ctx = WorkflowContext.current();
            var a = steps.a(key);
            ctx.version("path", 2, 2);
            var b = steps.inserted(key);
            var go = ctx.awaitSignal(SIGNAL, String.class, Duration.ofHours(1));
            return a + "|" + b + "|" + go + "|" + steps.c(key);
        }
    }

    /** {@link SagaV1} with {@code charge}'s compensation renamed to {@code refundPayment}. */
    @DurableWorkflow(name = TYPE)
    public static class SagaRenamedCompensation {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        RenamedRefundSagaSteps saga;

        @Saga
        @WorkflowMethod
        public String run(String key) {
            saga.reserve(key);
            saga.charge(key);
            return saga.ship(key);
        }
    }

    /** {@link SagaV1} with {@code @Saga(parallelCompensation = true)}. */
    @DurableWorkflow(name = TYPE)
    public static class SagaParallelCompensation {
        @ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 1))
        SagaSteps saga;

        @Saga(parallelCompensation = true)
        @WorkflowMethod
        public String run(String key) {
            saga.reserve(key);
            saga.charge(key);
            return saga.ship(key);
        }
    }

    // ── Cases ──────────────────────────────────────────────────────────────

    private final Recorder recorder = new Recorder();
    private final List<MaestroEngineHarness> wedged = new ArrayList<>();
    private final CountDownLatch wedge = new CountDownLatch(1);

    @AfterEach
    void openWedgeAndCloseWedgedNodes() {
        wedge.countDown();
        wedged.forEach(MaestroEngineHarness::close);
    }

    @Nested
    @DisplayName("a change the playbook calls safe: the edited code resumes the parked run and completes it")
    class Safe {

        @Test
        @DisplayName("a step inserted before the park behind version(): the parked run keeps the old path")
        void aGatedInsertionResumesTheParkedRun() throws Exception {
            var parked = parkOn(Base.class, "k1");
            resumeOn(parked, GatedInsertion.class, "a:k1|b:k1|ok|c:k1", List.of("c:k1"));
        }

        @Test
        @DisplayName("a step replaced before the park behind version(): the parked run keeps the old step")
        void aGatedReplacementResumesTheParkedRun() throws Exception {
            var parked = parkOn(Base.class, "k2");
            resumeOn(parked, GatedReplacement.class, "a:k2|b:k2|ok|c:k2", List.of("c:k2"));
        }

        @Test
        @DisplayName("maxSupported raised: the parked run keeps the version it recorded")
        void aRaisedMaxSupportedResumesTheParkedRunOnItsRecordedVersion() throws Exception {
            var parked = parkOn(VersionedV1.class, "k3");
            resumeOn(parked, RaisedMaxSupported.class, "a:k3|b:k3|ok|c:k3|v1", List.of("c:k3"));
        }

        @Test
        @DisplayName("an activity interface renamed with its recorded group pinned by @Activity(name)")
        void aRenamedInterfaceWithItsGroupPinnedResumesTheParkedRun() throws Exception {
            var parked = parkOn(PaymentV1.class, "k4");
            resumeOn(parked, RenamedInterfacePinned.class, "charge:k4|ok|capture:k4", List.of("capture:k4"));
        }
    }

    @Nested
    @DisplayName("a change the playbook calls unsafe: the run stands down as documented, keeps its signal, and the old code finishes it")
    class Unsafe {

        @Test
        @DisplayName("an activity renamed: nondeterministic_history at its slot")
        void aRenamedActivityStandsDown() throws Exception {
            var parked = parkOn(Base.class, "u1");
            standsDownOn(parked, RenamedActivity.class, StandDownReason.NONDETERMINISTIC_HISTORY,
                    exact("seq=1 expected=change.renamedA stored=change.a type=ACTIVITY_COMPLETED"));
            rollBackTo(parked, Base.class, "a:u1|b:u1|ok|c:u1");
        }

        @Test
        @DisplayName("two activities reordered: nondeterministic_history at the first")
        void reorderedActivitiesStandDown() throws Exception {
            var parked = parkOn(Base.class, "u2");
            standsDownOn(parked, Reordered.class, StandDownReason.NONDETERMINISTIC_HISTORY,
                    exact("seq=1 expected=change.b stored=change.a type=ACTIVITY_COMPLETED"));
            rollBackTo(parked, Base.class, "a:u2|b:u2|ok|c:u2");
        }

        @Test
        @DisplayName("a step inserted before the park without version(): nondeterministic_history at the insertion")
        void anUngatedInsertionStandsDown() throws Exception {
            var parked = parkOn(Base.class, "u3");
            standsDownOn(parked, UngatedInsertion.class, StandDownReason.NONDETERMINISTIC_HISTORY,
                    exact("seq=2 expected=change.inserted stored=change.b type=ACTIVITY_COMPLETED"));
            rollBackTo(parked, Base.class, "a:u3|b:u3|ok|c:u3");
        }

        @Test
        @DisplayName("a step removed before the park: nondeterministic_history where it was")
        void aRemovedStepStandsDown() throws Exception {
            var parked = parkOn(Base.class, "u4");
            standsDownOn(parked, Removed.class, StandDownReason.NONDETERMINISTIC_HISTORY,
                    exact("seq=2 expected=$maestro:awaitSignal:go stored=change.b type=ACTIVITY_COMPLETED"));
            rollBackTo(parked, Base.class, "a:u4|b:u4|ok|c:u4");
        }

        @Test
        @DisplayName("the awaited signal renamed: nondeterministic_history at the await")
        void aRenamedSignalStandsDown() throws Exception {
            var parked = parkOn(Base.class, "u5");
            standsDownOn(parked, RenamedSignal.class, StandDownReason.NONDETERMINISTIC_HISTORY,
                    exact("seq=3 expected=$maestro:awaitSignal:go-now stored=$maestro:awaitSignal:go type=TIMER_SCHEDULED"));
            rollBackTo(parked, Base.class, "a:u5|b:u5|ok|c:u5");
        }

        @Test
        @DisplayName("a sleep() where an activity ran: nondeterministic_history at that slot")
        void aSleepWhereAnActivityRanStandsDown() throws Exception {
            var parked = parkOn(Base.class, "u6");
            standsDownOn(parked, SleepWhereAnActivityRan.class, StandDownReason.NONDETERMINISTIC_HISTORY,
                    exact("seq=2 expected=$maestro:sleep stored=change.b type=ACTIVITY_COMPLETED"));
            assertThat(timerRows(parked.instanceId())).as("no timer row saved before the stand-down")
                    .containsExactly("await-signal PENDING");
            rollBackTo(parked, Base.class, "a:u6|b:u6|ok|c:u6");
        }

        @Test
        @DisplayName("parallel() forking a different number of branches: nondeterministic_history at the fork")
        void aChangedParallelArityStandsDown() throws Exception {
            var parked = parkOn(ParallelV1.class, "u7");
            standsDownOn(parked, ParallelArityChanged.class, StandDownReason.NONDETERMINISTIC_HISTORY,
                    exact("seq=1 expected=$maestro:parallel branches=3 stored=$maestro:parallel branches=2"));
            rollBackTo(parked, ParallelV1.class, "[a:u7-1, a:u7-2]|ok|c:u7");
        }

        @Test
        @DisplayName("an activity's argument built differently: nondeterministic_history with an argsHash detail")
        void aChangedArgumentStandsDown() throws Exception {
            var parked = parkOn(Base.class, "u8");
            standsDownOn(parked, ChangedArgument.class, StandDownReason.NONDETERMINISTIC_HISTORY,
                    "seq=1 step=change.a argsHash expected=[0-9a-f]{8} stored=[0-9a-f]{8}");
            rollBackTo(parked, Base.class, "a:u8|b:u8|ok|c:u8");
        }

        @Test
        @DisplayName("an activity's result type changed incompatibly: unknown_event_payload")
        void anIncompatibleResultTypeStandsDown() throws Exception {
            var parked = parkOn(QuoteTextV1.class, "u9");
            standsDownOn(parked, QuoteNumber.class, StandDownReason.UNKNOWN_EVENT_PAYLOAD,
                    "seq=1 cause=tools\\.jackson\\.databind\\.exc\\.InvalidFormatException.*");
            rollBackTo(parked, QuoteTextV1.class, "quote:u9|ok");
        }

        @Test
        @DisplayName("the workflow's input shape changed incompatibly: unknown_event_payload at sequence 0")
        void anIncompatibleInputShapeStandsDown() throws Exception {
            var parked = parkOn(Base.class, "u10");
            standsDownOn(parked, ChangedInputShape.class, StandDownReason.UNKNOWN_EVENT_PAYLOAD,
                    "seq=0 cause=tools\\.jackson\\.databind\\.exc\\.MismatchedInputException.*");
            rollBackTo(parked, Base.class, "a:u10|b:u10|ok|c:u10");
        }

        @Test
        @DisplayName("a compensation renamed under a COMPENSATING saga: nondeterministic_history at the recorded entry")
        void aRenamedCompensationStandsDownACompensatingSaga() throws Exception {
            var compensating = compensatingOn(SagaV1.class, "s1");
            standsDownCompensatingOn(compensating, SagaRenamedCompensation.class,
                    exact("seq=5000 expected=change-saga.refundPayment stored=change-saga.refund type=COMPENSATION_STEP_COMPLETED"));
            rollBackCompensatingTo(compensating, SagaV1.class);
        }

        @Test
        @DisplayName("@Saga(parallelCompensation) flipped under a COMPENSATING saga: nondeterministic_history")
        void aFlippedParallelCompensationStandsDownACompensatingSaga() throws Exception {
            var compensating = compensatingOn(SagaV1.class, "s2");
            standsDownCompensatingOn(compensating, SagaParallelCompensation.class,
                    exact("seq=5000 expected=$maestro:parallel-compensation stored=change-saga.refund type=COMPENSATION_STEP_COMPLETED"));
            rollBackCompensatingTo(compensating, SagaV1.class);
        }

        /**
         * Issue 89 ({@code docs/open-issues.md}): §10.2 says an instance whose recorded
         * version is below a raised {@code minSupported} "fails with
         * {@code UnsupportedWorkflowVersionException} ... ends {@code FAILED}
         * and saga compensation runs". For a run that recorded steps after its
         * {@code version()} call — every parked run — the engine instead
         * appends the failure at the replay's sequence, which a recorded step
         * already holds ({@code append collision at sequence 3}: seq 1 {@code a},
         * seq 2 the marker, seq 3 {@code b}), and the run stands down as
         * {@code STALE_RUN} — again on the next recovery pass. This pins what
         * the engine does; the documented outcome is
         * {@link #raisingTheFloorAboveARecordedVersionFailsTheRunAsDocumented},
         * disabled until Issue 89 is decided (and to be deleted, not enabled,
         * if the decision keeps a stand-down).
         */
        @Test
        @DisplayName("minSupported raised above a parked run's recorded version: today it stands down as stale_run on every pass (Issue 89), never FAILED, signal kept")
        void raisingTheFloorAboveARecordedVersionStandsDownToday() throws Exception {
            var parked = parkOn(VersionedV1.class, "m1");
            standsDownOn(parked, RaisedFloor.class, StandDownReason.STALE_RUN,
                    exact("append collision at sequence 3"), 2);
            rollBackTo(parked, VersionedV1.class, "a:m1|b:m1|ok|c:m1|v1");
        }

        @Disabled("Issue 89 (docs/open-issues.md): the run stands down as STALE_RUN instead of failing as operations"
                + " §10.2 documents; delete this test rather than enable it if Issue 89 keeps a stand-down")
        @Test
        @DisplayName("minSupported raised above a parked run's recorded version: the run FAILS, as §10.2 documents")
        void raisingTheFloorAboveARecordedVersionFailsTheRunAsDocumented() throws Exception {
            var parked = parkOn(VersionedV1.class, "m2");
            var observer = new RecordingObserver();
            try (var nodeB = node(RaisedFloor.class, observer)) {
                nodeB.deliverSignal(parked.workflowId(), SIGNAL, "ok");
                assertThat(nodeB.recover()).isEqualTo(1);
                assertThat(awaitTerminal(parked.workflowId(), BOUND).status()).isEqualTo(WorkflowStatus.FAILED);
            }
            assertThat(observer.standDowns()).isEmpty();
            assertThat(observer.failed()).containsExactly(parked.workflowId());
            var last = eventsOf(parked.instanceId()).getLast();
            assertThat(last.eventType()).isEqualTo(EventType.WORKFLOW_FAILED);
            assertThat(String.valueOf(last.payload())).as("the failure names the documented exception")
                    .contains("UnsupportedWorkflowVersionException");
            assertThat(eventsOf(parked.instanceId()).subList(0, parked.events().size()))
                    .as("the history the old code wrote is untouched").isEqualTo(parked.events());
            assertThat(recorder.calls).as("nothing ran again").containsExactly("a:m2", "b:m2");
        }
    }

    // ── Flow ───────────────────────────────────────────────────────────────

    /** What node A left: the run parked on {@code go}, its history and the calls it made. */
    private record Parked(String workflowId, UUID instanceId, List<WorkflowEvent> events, List<String> calls) {
    }

    /** Node A starts the run on the old code, waits until it parks on {@code go}, and shuts down. */
    private Parked parkOn(Class<?> oldCode, String key) {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("code-change-" + key);
        UUID instanceId;
        try (var nodeA = node(oldCode, null)) {
            var handle = nodeA.start(workflowId, oldCode, key);
            handle.awaitStatus(WorkflowStatus.WAITING_SIGNAL, BOUND);
            instanceId = handle.instanceId();
        }
        var events = eventsOf(instanceId);
        assertThat(events.getLast().eventType()).as("parked in its await").isEqualTo(EventType.TIMER_SCHEDULED);
        return new Parked(workflowId, instanceId, events, List.copyOf(recorder.calls));
    }

    /**
     * Safe: node B stores {@code go}, adopts the run on the edited code, and it
     * completes with {@code output}; only {@code liveCalls} ran on node B.
     */
    private void resumeOn(Parked parked, Class<?> editedCode, String output, List<String> liveCalls) {
        var observer = new RecordingObserver();
        try (var nodeB = node(editedCode, observer)) {
            nodeB.deliverSignal(parked.workflowId(), SIGNAL, "ok");
            assertThat(nodeB.recover()).isEqualTo(1);
            assertThat(awaitTerminal(parked.workflowId(), BOUND).status())
                    .as("stand-downs: %s", observer.standDowns()).isEqualTo(WorkflowStatus.COMPLETED);
        }
        assertThat(observer.standDowns()).isEmpty();
        assertThat(observer.abandoned()).isEmpty();
        assertThat(observer.failed()).isEmpty();
        assertThat(serializer.deserialize(store.getInstance(parked.workflowId()).orElseThrow().output(), String.class))
                .isEqualTo(output);
        assertThat(eventsOf(parked.instanceId()).subList(0, parked.events().size()))
                .as("the history the old code wrote is an unchanged prefix").isEqualTo(parked.events());
        var onB = recorder.calls.subList(parked.calls().size(), recorder.calls.size());
        assertThat(onB).as("recorded steps replayed; only the rest ran, once each").containsExactlyElementsOf(liveCalls);
        assertThat(signalRows(parked.workflowId())).containsExactly("go consumed=true");
    }

    /**
     * Unsafe: node B stores {@code go}, adopts the run on the edited code, and
     * it stands down with {@code reason} and a detail matching
     * {@code detailPattern} — writing nothing, failing nothing, compensating
     * nothing, running nothing again, consuming no signal.
     */
    private void standsDownOn(Parked parked, Class<?> editedCode, StandDownReason reason, String detailPattern) {
        standsDownOn(parked, editedCode, reason, detailPattern, 1);
    }

    /** As above, over {@code passes} recovery passes, each adopting the run again and standing it down again. */
    private void standsDownOn(Parked parked, Class<?> editedCode, StandDownReason reason, String detailPattern,
                              int passes) {
        var observer = new RecordingObserver();
        try (var nodeB = node(editedCode, observer)) {
            nodeB.deliverSignal(parked.workflowId(), SIGNAL, "ok");
            for (int pass = 1; pass <= passes; pass++) {
                var expected = pass;
                assertThat(nodeB.recover()).as("pass %d adopts the run", pass).isEqualTo(1);
                await().atMost(BOUND).until(() -> observer.standDowns().size() >= expected);
                await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(parked.workflowId()));
            }
        }
        assertThat(observer.standDowns()).as(observer.callbackNames().toString()).hasSize(passes).allSatisfy(call -> {
            assertThat(call.reason()).isEqualTo(reason);
            assertThat(call.detail()).matches(detailPattern);
        });
        assertThat(observer.failed()).as("no failure recorded").isEmpty();
        assertThat(observer.compensating()).as("no compensation").isEmpty();
        assertThat(store.getInstance(parked.workflowId()).orElseThrow().status())
                .as("the status the run had").isEqualTo(WorkflowStatus.WAITING_SIGNAL);
        assertThat(eventsOf(parked.instanceId())).as("a stand-down writes nothing").isEqualTo(parked.events());
        assertThat(recorder.calls).as("no activity ran again").isEqualTo(parked.calls());
        assertThat(signalRows(parked.workflowId())).as("the signal is kept").containsExactly("go consumed=false");
    }

    /** The playbook's first remedy: node C runs the old code again, adopts the run and completes it. */
    private void rollBackTo(Parked parked, Class<?> oldCode, String output) {
        var observer = new RecordingObserver();
        try (var nodeC = node(oldCode, observer)) {
            assertThat(nodeC.recover()).isEqualTo(1);
            assertThat(awaitTerminal(parked.workflowId(), BOUND).status())
                    .as("stand-downs: %s", observer.standDowns()).isEqualTo(WorkflowStatus.COMPLETED);
        }
        assertThat(observer.standDowns()).isEmpty();
        assertThat(serializer.deserialize(store.getInstance(parked.workflowId()).orElseThrow().output(), String.class))
                .isEqualTo(output);
        assertThat(signalRows(parked.workflowId())).as("the kept signal, consumed by the old code")
                .containsExactly("go consumed=true");
    }

    /** What node A left: a saga {@code COMPENSATING}, its first compensation recorded, the second in flight. */
    private record Compensating(String workflowId, UUID instanceId, List<WorkflowEvent> events, List<String> calls) {
    }

    /**
     * Node A (its own in-memory lock, so node B can adopt while it is held)
     * runs the old saga until {@code ship} fails, records the first
     * compensation ({@code refund}) and is held inside the second
     * ({@code release}).
     */
    private Compensating compensatingOn(Class<?> oldCode, String key) throws Exception {
        var entered = new CountDownLatch(1);
        recorder.enteredRelease = entered;
        recorder.wedgeRelease = wedge;
        var nodeA = MaestroEngineHarness.builder(store, objectMapper).serviceName(SERVICE).build();
        wedged.add(nodeA);
        nodeA.registerWorkflow(oldCode);
        registerActivities(nodeA);
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("code-change-" + key);
        var handle = nodeA.start(workflowId, oldCode, key);
        assertThat(entered.await(30, TimeUnit.SECONDS)).as("node A reached the second compensation").isTrue();
        recorder.enteredRelease = null;
        var instanceId = handle.instanceId();
        await().atMost(BOUND).until(() -> store.getEventBySequence(instanceId, 5000).isPresent());
        assertThat(handle.status()).isEqualTo(WorkflowStatus.COMPENSATING);
        return new Compensating(workflowId, instanceId, eventsOf(instanceId), List.copyOf(recorder.calls));
    }

    /** Unsafe, for a compensating saga: node B adopts it on the edited code and stands down. */
    private void standsDownCompensatingOn(Compensating saga, Class<?> editedCode, String detailPattern) {
        var observer = new RecordingObserver();
        try (var nodeB = node(editedCode, observer)) {
            assertThat(nodeB.recover()).isEqualTo(1);
            await().atMost(BOUND).until(() -> !observer.standDowns().isEmpty());
            await().atMost(BOUND).until(() -> !nodeB.executor().isRunning(saga.workflowId()));
        }
        assertThat(observer.standDowns()).as(observer.callbackNames().toString()).singleElement().satisfies(call -> {
            assertThat(call.reason()).isEqualTo(StandDownReason.NONDETERMINISTIC_HISTORY);
            assertThat(call.detail()).matches(detailPattern);
        });
        assertThat(observer.failed()).as("no failure recorded").isEmpty();
        assertThat(store.getInstance(saga.workflowId()).orElseThrow().status())
                .as("the status the run had").isEqualTo(WorkflowStatus.COMPENSATING);
        assertThat(eventsOf(saga.instanceId())).as("a stand-down writes nothing").isEqualTo(saga.events());
        assertThat(recorder.calls).as("no compensation ran again on node B").isEqualTo(saga.calls());
    }

    /**
     * The first remedy for a compensating saga: node C runs the old code,
     * replays the recorded compensation, runs the one node A was held in (the
     * at-least-once window: node A started it too), and ends the run
     * {@code FAILED} by its own failure.
     */
    private void rollBackCompensatingTo(Compensating saga, Class<?> oldCode) {
        var observer = new RecordingObserver();
        try (var nodeC = node(oldCode, observer)) {
            assertThat(nodeC.recover()).isEqualTo(1);
            assertThat(awaitTerminal(saga.workflowId(), BOUND).status())
                    .as("stand-downs: %s", observer.standDowns()).isEqualTo(WorkflowStatus.FAILED);
        }
        assertThat(observer.standDowns()).isEmpty();
        var heldIn = saga.calls().getLast();
        assertThat(heldIn).startsWith("release:");
        assertThat(recorder.calls.subList(saga.calls().size(), recorder.calls.size()))
                .as("only the compensation node A was held in ran again").containsExactly(heldIn);
        assertThat(eventsOf(saga.instanceId()).stream().map(e -> e.eventType().name()).toList())
                .as("the saga's own failure ends it").endsWith("COMPENSATION_COMPLETED", "WORKFLOW_FAILED")
                .doesNotContain("COMPENSATION_STEP_FAILED");
    }

    // ── Nodes and reads ────────────────────────────────────────────────────

    /** A detail pattern that matches exactly {@code detail}, which carries {@code $}. */
    private static String exact(String detail) {
        return Pattern.quote(detail);
    }

    private MaestroEngineHarness node(Class<?> workflow, @Nullable RecordingObserver observer) {
        var builder = MaestroEngineHarness.builder(store, objectMapper)
                .serviceName(SERVICE)
                .lock(newLock())
                .instanceLockTtl(Duration.ofSeconds(10));
        if (observer != null) {
            builder.observer(observer);
        }
        var node = builder.build();
        node.registerWorkflow(workflow);
        registerActivities(node);
        return node;
    }

    private void registerActivities(MaestroEngineHarness node) {
        registerEach(node, List.of(
                n -> n.registerActivities(Steps.class, recorder),
                n -> n.registerActivities(PaymentSteps.class, recorder),
                n -> n.registerActivities(BillingSteps.class, recorder),
                n -> n.registerActivities(QuoteAsText.class, recorder),
                n -> n.registerActivities(QuoteAsNumber.class, key -> {
                    recorder.calls.add("quote-as-number:" + key);
                    return 42;
                }),
                n -> n.registerActivities(SagaSteps.class, recorder),
                n -> n.registerActivities(RenamedRefundSagaSteps.class, recorder)));
    }

    private static void registerEach(MaestroEngineHarness node, List<Consumer<MaestroEngineHarness>> registrations) {
        registrations.forEach(r -> r.accept(node));
    }

    private List<WorkflowEvent> eventsOf(UUID instanceId) {
        return store.getEvents(instanceId);
    }

    /** Every signal row of the workflow: {@code "<name> consumed=<b>"}. */
    private List<String> signalRows(String workflowId) {
        var rows = new ArrayList<String>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT signal_name, consumed FROM maestro_workflow_signal"
                     + " WHERE workflow_id = ? ORDER BY received_at, id")) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + " consumed=" + rs.getBoolean(2));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rows;
    }

    /** Every timer row of the instance: {@code "<timer_id prefix> <status>"}. */
    private List<String> timerRows(UUID instanceId) throws SQLException {
        var rows = new ArrayList<String>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT timer_id, status FROM maestro_workflow_timer"
                     + " WHERE workflow_instance_id = ? ORDER BY fire_at")) {
            ps.setObject(1, instanceId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1).replaceAll("-\\d+$", "") + " " + rs.getString(2));
                }
            }
        }
        return rows;
    }
}
