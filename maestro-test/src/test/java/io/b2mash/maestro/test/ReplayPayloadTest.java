package io.b2mash.maestro.test;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.StandDownReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recovery deserialises every persisted activity result. A type the live
 * path returns happily but Jackson cannot read back fails only there — which,
 * before the kit could replay, meant only on a production restart.
 */
class ReplayPayloadTest {

    private TestWorkflowEnvironment env;
    private CountingDecider decider;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        decider = new CountingDecider();
        env.registerActivities(Decider.class, decider);
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void sealedResultWithTypeInfo_replaysAndTheWorkflowResultReadsBack() throws Exception {
        var crash = env.crashAfter(1);
        var handle = env.startWorkflow(TypedWorkflow.class, "alice");

        crash.recover();

        var result = handle.getResult(Decision.class, Duration.ofSeconds(10));
        assertEquals(new Approved("alice"), result);
        assertEquals(1, handle.replays("Decider.decide"));
        assertEquals(1, decider.decideCalls.get(), "replayed, not re-executed");
        assertEquals(1, decider.auditCalls.get());
    }

    @Test
    void sealedResultWithoutTypeInfo_passesLiveButCannotBeReplayed() throws Exception {
        // Live: the same shape runs to completion — nothing reads the result back.
        var live = env.startWorkflow(BareWorkflow.class, "bob");
        assertEquals("audited", live.getResult(String.class, Duration.ofSeconds(10)));

        var crash = env.crashAfter(1);
        var handle = env.startWorkflow(BareWorkflow.class, "carol");
        crash.recover();

        var standDown = handle.awaitStandDown(Duration.ofSeconds(10));
        assertEquals(StandDownReason.UNKNOWN_EVENT_PAYLOAD, standDown.reason());
        assertTrue(handle.getStatus().isActive(), "a stand-down records no outcome");
        assertEquals(WorkflowStatus.RUNNING, handle.getStatus());
        assertEquals(2, decider.bareCalls.get(), "once per run — the recovered run re-executed nothing");
        assertEquals(1, decider.auditCalls.get(), "only the live run reached the audit");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    /** Carries its subtype in the payload, so Jackson can read it back as the interface. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Approved.class, name = "approved"),
            @JsonSubTypes.Type(value = Rejected.class, name = "rejected")
    })
    public sealed interface Decision permits Approved, Rejected {
    }

    public record Approved(String by) implements Decision {
    }

    public record Rejected(String reason) implements Decision {
    }

    /** The same shape without type information: serialisable, not deserialisable. */
    public sealed interface BareDecision permits BareApproved {
    }

    public record BareApproved(String by) implements BareDecision {
    }

    @Activity
    public interface Decider {
        Decision decide(String applicant);

        BareDecision decideBare(String applicant);

        void audit(String note);
    }

    static final class CountingDecider implements Decider {
        final AtomicInteger decideCalls = new AtomicInteger();
        final AtomicInteger bareCalls = new AtomicInteger();
        final AtomicInteger auditCalls = new AtomicInteger();

        @Override
        public Decision decide(String applicant) {
            decideCalls.incrementAndGet();
            return new Approved(applicant);
        }

        @Override
        public BareDecision decideBare(String applicant) {
            bareCalls.incrementAndGet();
            return new BareApproved(applicant);
        }

        @Override
        public void audit(String note) {
            auditCalls.incrementAndGet();
        }
    }

    @DurableWorkflow(name = "typed-decision")
    public static class TypedWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Decider decider;

        @WorkflowMethod
        public Decision run(String applicant) {
            var decision = decider.decide(applicant);
            decider.audit(decision.toString());
            return decision;
        }
    }

    @DurableWorkflow(name = "bare-decision")
    public static class BareWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Decider decider;

        @WorkflowMethod
        public String run(String applicant) {
            var decision = decider.decideBare(applicant);
            decider.audit(decision.toString());
            return "audited";
        }
    }
}
