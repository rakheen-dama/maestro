package io.b2mash.maestro.samples.document.workflow;

import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.samples.document.activity.DocumentActivities;
import io.b2mash.maestro.samples.document.domain.DocumentInput;
import io.b2mash.maestro.samples.document.domain.DocumentResult;
import io.b2mash.maestro.samples.document.domain.DocumentStatus;
import io.b2mash.maestro.samples.document.domain.ReviewDecision;
import io.b2mash.maestro.test.TestWorkflowEnvironment;
import io.b2mash.maestro.test.TestWorkflowHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-memory tests for {@link DocumentApprovalWorkflow} using
 * {@link TestWorkflowEnvironment}, including the per-run state of its
 * {@code currentStep} query field (F1: one shared workflow object made every
 * running instance report the step of whichever ran last).
 */
class DocumentApprovalWorkflowTest {

    private TestWorkflowEnvironment env;
    private LatchingDocumentActivities activities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        activities = new LatchingDocumentActivities();
        env.registerActivities(DocumentActivities.class, activities);
    }

    @AfterEach
    void tearDown() {
        if (activities != null) {
            activities.releaseAll();
        }
        if (env != null) {
            env.shutdown();
        }
    }

    @Test
    void approvedDocumentIsPublished() throws TimeoutException {
        var h = env.startWorkflow(DocumentApprovalWorkflow.class, new DocumentInput("T", "body", "author-1"));
        awaitStatus(h, WorkflowStatus.WAITING_SIGNAL);

        h.signal("review.decision", new ReviewDecision(true, "rev-1", null));
        var r = h.getResult(DocumentResult.class, Duration.ofSeconds(10));

        assertTrue(r.success());
        assertEquals("PUBLISHED", r.status());
    }

    @Test
    void twoInstancesReportTheirOwnStep() {
        activities.block("B");
        var a = env.startWorkflow(DocumentApprovalWorkflow.class, new DocumentInput("A", "body", "author-1"));
        var b = env.startWorkflow(DocumentApprovalWorkflow.class, new DocumentInput("B", "body", "author-2"));
        awaitStatus(a, WorkflowStatus.WAITING_SIGNAL);
        activities.awaitBlocked("B");

        assertEquals("AWAITING_REVIEW", a.query("getStatus", null, DocumentStatus.class).currentStep());
        assertEquals("ASSIGNING_REVIEWER", b.query("getStatus", null, DocumentStatus.class).currentStep());

        activities.release("B");
        awaitStatus(b, WorkflowStatus.WAITING_SIGNAL);
        assertEquals("AWAITING_REVIEW", b.query("getStatus", null, DocumentStatus.class).currentStep());
    }

    private static void awaitStatus(TestWorkflowHandle handle, WorkflowStatus status) {
        await().atMost(Duration.ofSeconds(10)).until(() -> handle.getStatus() == status);
    }

    /**
     * Behaves like {@code InMemoryDocumentActivities} without the simulated
     * latency, except that {@code assignReviewer} blocks while the document's
     * title is {@linkplain #block held}. The document ID embeds the title, so
     * {@code assignReviewer} can tell which run is calling it.
     */
    static final class LatchingDocumentActivities implements DocumentActivities {

        private static final String ID_PREFIX = "doc-";

        private final ConcurrentHashMap<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, CountDownLatch> arrivals = new ConcurrentHashMap<>();

        /** Makes {@code assignReviewer} for the document titled {@code title} block until released. */
        void block(String title) {
            gates.put(title, new CountDownLatch(1));
            arrivals.put(title, new CountDownLatch(1));
        }

        /** Waits until the run for {@code title} is blocked inside {@code assignReviewer}. */
        void awaitBlocked(String title) {
            try {
                assertTrue(arrivals.get(title).await(10, TimeUnit.SECONDS),
                        "the run for '" + title + "' never reached assignReviewer");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        /** Lets the blocked {@code assignReviewer} for {@code title} return. */
        void release(String title) {
            gates.get(title).countDown();
        }

        void releaseAll() {
            gates.values().forEach(CountDownLatch::countDown);
        }

        @Override
        public String validateAndStore(DocumentInput input) {
            if (input.title() == null || input.title().isBlank()) {
                throw new IllegalArgumentException("Document title must not be blank");
            }
            return ID_PREFIX + input.title();
        }

        @Override
        public String assignReviewer(String documentId) {
            var title = documentId.substring(ID_PREFIX.length());
            var gate = gates.get(title);
            if (gate != null) {
                arrivals.get(title).countDown();
                try {
                    gate.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return "reviewer-alice";
        }

        @Override
        public void publishDocument(String documentId) {
            // no-op
        }

        @Override
        public void notifyRejection(String documentId, String authorId, String reason) {
            // no-op
        }

        @Override
        public void archiveAuditTrail(String documentId, String finalStatus) {
            // no-op
        }
    }
}
