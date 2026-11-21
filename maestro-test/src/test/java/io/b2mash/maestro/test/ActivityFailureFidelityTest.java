package io.b2mash.maestro.test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.FailureDetails;
import io.b2mash.maestro.core.annotation.RetryPolicy;
import io.b2mash.maestro.core.annotation.Saga;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.ActivityExecutionException;
import io.b2mash.maestro.core.exception.ActivityTimeoutException;
import io.b2mash.maestro.core.exception.ReconstructedActivityFailure;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 42, end to end in the kit: the {@code catch} block of a workflow
 * observes the same activity failure — cause class, message, the wrapper's
 * message, the recorded failure and its properties — on the first run and
 * after a crash and {@code recover()}, because the first run throws the
 * failure rebuilt from its record, exactly as a replay does.
 */
class ActivityFailureFidelityTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        Seen.clear();
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Failing.class, new FailingImpl());
        env.registerActivities(Sink.class, observation -> observation);
        env.registerActivities(Shop.class, new ShopImpl());
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    enum Case {
        /** {@code @FailureDetails} with a creator: rebuilt as itself, properties included. */
        OPTED_IN("cause=" + Declined.class.getName() + "|msg=declined|aee=Activity 'Failing.optedIn' failed in "
                + "workflow 'fidelity-OPTED_IN': declined|type=" + Declined.class.getName()
                + "|details={\"reasonCode\":\"51\",\"amount\":1.0E7}|causedBy=true|code=51/1.0E7|causeOfCause=null"),
        /** Not opted in, public (String) constructor: itself, type and message only. */
        NOT_OPTED_IN("cause=" + WithStringCtor.class.getName() + "|msg=boom|aee=Activity 'Failing.notOptedIn' "
                + "failed in workflow 'fidelity-NOT_OPTED_IN': boom|type=" + WithStringCtor.class.getName()
                + "|details={}|causedBy=false|extra=null|causeOfCause=null"),
        /** No (String) constructor: a reconstruction on the first run too. */
        NO_STRING_CTOR("cause=" + ReconstructedActivityFailure.class.getName() + "|msg=boom|aee=Activity "
                + "'Failing.noStringCtor' failed in workflow 'fidelity-NO_STRING_CTOR': boom|type="
                + NoStringCtor.class.getName() + "|details={}|causedBy=false|orig=" + NoStringCtor.class.getName()
                + "|causeOfCause=null"),
        /** A null message is null on both paths, never "Replayed failure". */
        NULL_MESSAGE("cause=" + WithStringCtor.class.getName() + "|msg=null|aee=Activity 'Failing.nullMessage' "
                + "failed in workflow 'fidelity-NULL_MESSAGE': " + WithStringCtor.class.getName() + "|type="
                + WithStringCtor.class.getName() + "|details={}|causedBy=false|extra=null|causeOfCause=null"),
        /** Opted in but not rebuildable: a reconstruction on both paths, properties still readable. */
        UNFAITHFUL("cause=" + ReconstructedActivityFailure.class.getName() + "|msg=m|aee=Activity "
                + "'Failing.unfaithful' failed in workflow 'fidelity-UNFAITHFUL': m|type="
                + FinalNoCreator.class.getName() + "|details={\"code\":\"c-1\"}|causedBy=false|orig="
                + FinalNoCreator.class.getName() + "|causeOfCause=null"),
        /** A timed-out attempt: ActivityTimeoutException, opted in by the engine. */
        TIMEOUT("cause=" + ActivityTimeoutException.class.getName() + "|msg=Activity 'Failing.timesOut' in "
                + "workflow 'fidelity-TIMEOUT' exceeded its startToCloseTimeout of PT0.2S|aee=Activity "
                + "'Failing.timesOut' failed in workflow 'fidelity-TIMEOUT': Activity 'Failing.timesOut' in workflow "
                + "'fidelity-TIMEOUT' exceeded its startToCloseTimeout of PT0.2S|type="
                + ActivityTimeoutException.class.getName() + "|details={\"workflowId\":\"fidelity-TIMEOUT\","
                + "\"activityName\":\"Failing.timesOut\",\"timeout\":\"PT0.2S\"}|causedBy=false|timeout=PT0.2S"
                + "|causeOfCause=null");

        final String expected;

        Case(String expected) {
            this.expected = expected;
        }
    }

    @ParameterizedTest
    @EnumSource(Case.class)
    void theCatchBlockSeesTheSameFailureLiveAndAfterRecover(Case c) throws Exception {
        var id = "fidelity-" + c.name();
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.ACTIVITY_COMPLETED
                && "Sink.take".equals(e.stepName()));
        var handle = env.startWorkflow(id, FailureWorkflow.class, c.name());
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(10)),
                "standDowns=" + handle.standDowns());
        assertEquals(List.of(c.expected, c.expected), Seen.of(id), "[live, after recover]");
    }

    @Test
    void aFailureInsideAParallelBranchIsTheSameLiveAndAfterRecover() throws Exception {
        var id = "fidelity-parallel";
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.ACTIVITY_COMPLETED
                && "Sink.take".equals(e.stepName()));
        var handle = env.startWorkflow(id, ParallelWorkflow.class, "x");
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();

        assertEquals(WorkflowStatus.COMPLETED, handle.awaitTerminal(Duration.ofSeconds(10)),
                "standDowns=" + handle.standDowns());
        var seen = Seen.of(id);
        assertEquals(2, seen.size(), seen.toString());
        assertEquals(seen.get(0), seen.get(1), "[live, after recover]");
        assertTrue(seen.getFirst().startsWith("cause=" + Declined.class.getName() + "|msg=declined|"), seen.getFirst());
        assertTrue(seen.getFirst().contains("|code=51/1.0E7|"), seen.getFirst());
    }

    @Test
    void aFailureASagaCatchesAndRethrowsIsTheSameLiveAndAfterRecover() throws Exception {
        var id = "fidelity-saga";
        var crash = env.crashAfterPersisting(e -> e.eventType() == EventType.COMPENSATION_STEP_COMPLETED);
        var handle = env.startWorkflow(id, SagaWorkflow.class, "sku-1");
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();

        assertEquals(WorkflowStatus.FAILED, handle.awaitTerminal(Duration.ofSeconds(10)));
        var seen = Seen.of(id);
        assertEquals(2, seen.size(), seen.toString());
        assertEquals(seen.get(0), seen.get(1), "[live, after recover]");
        assertTrue(seen.getFirst().contains("|code=51/1.0E7|"), seen.getFirst());
        var failure = handle.getFailure().orElseThrow();
        assertEquals(OrderFailed.class.getName(), failure.exceptionType());
        assertEquals("declined with 51", failure.message());
        assertEquals(List.of("res-sku-1"), ShopImpl.RELEASED, "the reservation was released once");
    }

    // ── What the catch block records ─────────────────────────────────────

    static String describe(ActivityExecutionException e) {
        var cause = e.getCause();
        var out = new StringBuilder()
                .append("cause=").append(cause.getClass().getName())
                .append("|msg=").append(cause.getMessage())
                .append("|aee=").append(e.getMessage())
                .append("|type=").append(e.failure().type())
                .append("|details=").append(e.failure().details())
                .append("|causedBy=").append(e.causedBy(Declined.class));
        switch (cause) {
            case Declined d -> out.append("|code=").append(d.getReasonCode()).append('/').append(d.getAmount());
            case WithStringCtor w -> out.append("|extra=").append(w.extra);
            case ReconstructedActivityFailure r -> out.append("|orig=").append(r.originalClassName());
            case ActivityTimeoutException t -> out.append("|timeout=").append(t.timeout());
            default -> {
            }
        }
        return out.append("|causeOfCause=").append(cause.getCause()).toString();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    @Activity
    public interface Failing {
        String optedIn();

        String notOptedIn();

        String noStringCtor();

        String nullMessage();

        String unfaithful();

        String timesOut();

        String ok();
    }

    static final class FailingImpl implements Failing {
        @Override
        public String optedIn() {
            var e = new Declined("declined", "51", 1.0E7);
            e.initCause(new java.io.IOException("socket"));
            throw e;
        }

        @Override
        public String notOptedIn() {
            throw new WithStringCtor("boom", "only in memory", new IllegalStateException("root"));
        }

        @Override
        public String noStringCtor() {
            throw new NoStringCtor("boom", 7);
        }

        @Override
        public String nullMessage() {
            throw new WithStringCtor(null, "x", null);
        }

        @Override
        public String unfaithful() {
            throw new FinalNoCreator("m", "c-1");
        }

        @Override
        public String timesOut() {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }

        @Override
        public String ok() {
            return "ok";
        }
    }

    @Activity
    public interface Sink {
        String take(String observation);
    }

    @Activity
    public interface Shop {
        @Compensate("release")
        String reserve(String sku);

        void release(String reservation);

        String charge(String reservation);
    }

    static final class ShopImpl implements Shop {
        static final List<String> RELEASED = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String reserve(String sku) {
            return "res-" + sku;
        }

        @Override
        public void release(String reservation) {
            RELEASED.add(reservation);
        }

        @Override
        public String charge(String reservation) {
            throw new Declined("declined", "51", 1.0E7);
        }
    }

    @DurableWorkflow(name = "failure-fidelity")
    public static class FailureWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private Failing failing;

        @ActivityStub(startToCloseTimeout = "PT0.2S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private Failing slow;

        @ActivityStub(startToCloseTimeout = "PT10S")
        private Sink sink;

        @WorkflowMethod
        public String run(String caseName) {
            Callable<String> call = switch (Case.valueOf(caseName)) {
                case OPTED_IN -> failing::optedIn;
                case NOT_OPTED_IN -> failing::notOptedIn;
                case NO_STRING_CTOR -> failing::noStringCtor;
                case NULL_MESSAGE -> failing::nullMessage;
                case UNFAITHFUL -> failing::unfaithful;
                case TIMEOUT -> slow::timesOut;
            };
            String seen;
            try {
                call.call();
                seen = "no failure";
            } catch (ActivityExecutionException e) {
                seen = describe(e);
            } catch (Exception e) {
                seen = "unexpected " + e;
            }
            Seen.record(WorkflowContext.current().workflowId(), seen);
            return sink.take(seen);
        }
    }

    @DurableWorkflow(name = "failure-fidelity-parallel")
    public static class ParallelWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private Failing failing;

        @ActivityStub(startToCloseTimeout = "PT10S")
        private Sink sink;

        @WorkflowMethod
        public String run(String input) {
            String seen;
            try {
                WorkflowContext.current().parallel(List.<Callable<String>>of(failing::ok, failing::optedIn));
                seen = "no failure";
            } catch (ActivityExecutionException e) {
                seen = describe(e);
            }
            Seen.record(WorkflowContext.current().workflowId(), seen);
            return sink.take(seen);
        }
    }

    @DurableWorkflow(name = "failure-fidelity-saga")
    public static class SagaWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S", retryPolicy = @RetryPolicy(maxAttempts = 1))
        private Shop shop;

        @WorkflowMethod
        @Saga
        public String run(String sku) {
            var reservation = shop.reserve(sku);
            try {
                return shop.charge(reservation);
            } catch (ActivityExecutionException e) {
                Seen.record(WorkflowContext.current().workflowId(), describe(e));
                var code = e.causedBy(Declined.class) ? e.failure().details().path("reasonCode").asString() : "?";
                throw new OrderFailed("declined with " + code, e);
            }
        }
    }

    public static class OrderFailed extends RuntimeException {
        public OrderFailed(String message) {
            super(message);
        }

        public OrderFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FailureDetails
    public static class Declined extends RuntimeException {
        private final String reasonCode;
        private final double amount;

        @JsonCreator
        public Declined(@JsonProperty("message") String message, @JsonProperty("reasonCode") String reasonCode,
                        @JsonProperty("amount") double amount) {
            super(message);
            this.reasonCode = reasonCode;
            this.amount = amount;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public double getAmount() {
            return amount;
        }
    }

    public static class WithStringCtor extends RuntimeException {
        final String extra;

        public WithStringCtor(String message) {
            this(message, null, null);
        }

        public WithStringCtor(String message, String extra, Throwable cause) {
            super(message, cause);
            this.extra = extra;
        }
    }

    public static class NoStringCtor extends RuntimeException {
        public NoStringCtor(String message, int code) {
            super(message);
        }
    }

    @FailureDetails
    public static class FinalNoCreator extends RuntimeException {
        private final String code;

        public FinalNoCreator(String message) {
            this(message, null);
        }

        public FinalNoCreator(String message, String code) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    static final class Seen {
        private static final ConcurrentHashMap<String, List<String>> SEEN = new ConcurrentHashMap<>();

        static void record(String workflowId, String value) {
            SEEN.computeIfAbsent(workflowId, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
        }

        static List<String> of(String workflowId) {
            var values = SEEN.get(workflowId);
            return values == null ? List.of() : new ArrayList<>(values);
        }

        static void clear() {
            SEEN.clear();
            ShopImpl.RELEASED.clear();
        }
    }
}
