package io.b2mash.maestro.messaging.postgres;

import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PostgresWorkflowMessaging} against a real PostgreSQL backend.
 *
 * <p>This module ships in releases but had no tests at all. The suite covers
 * the queue semantics the engine depends on: at-most-one-consumer claiming via
 * {@code FOR UPDATE SKIP LOCKED}, reclaim of stale {@code PROCESSING} rows, and
 * the round trips for tasks, signals and lifecycle events.
 *
 * <p>Polling threads are started by {@code subscribe}, so every assertion about
 * delivery is made through Awaitility rather than by assuming synchronous
 * hand-off.
 */
@DisplayName("PostgresWorkflowMessaging against a real PostgreSQL backend")
class PostgresWorkflowMessagingTest extends PostgresMessagingTestSupport {

    private static final Duration BOUND = Duration.ofSeconds(20);

    @Nested
    @DisplayName("task queue")
    class TaskQueueTests {

        @Test
        @DisplayName("a published task is delivered to a subscriber and marked COMPLETED")
        void publishedTaskIsDelivered() throws SQLException {
            var queue = taskQueueName();
            var received = new ConcurrentLinkedQueue<TaskMessage>();
            messaging.subscribe(queue, received::add);

            messaging.publishTask(queue, newTask("wf-task-1"));

            await().atMost(BOUND).until(() -> !received.isEmpty());
            assertEquals("wf-task-1", received.peek().workflowId());
            await().atMost(BOUND).until(
                    () -> "COMPLETED".equals(statusOf("maestro_task_queue", "wf-task-1")));
        }

        @Test
        @DisplayName("only one of two competing consumers claims a task — FOR UPDATE SKIP LOCKED")
        void competingConsumersClaimTaskOnce() throws Exception {
            var queue = taskQueueName();
            var deliveries = new AtomicInteger();
            var slowHandlerEntered = new CountDownLatch(1);

            // Two independent messaging instances model two nodes on one queue.
            var nodeB = new PostgresWorkflowMessaging(newDataSource(), objectMapper, notificationListener);
            try {
                messaging.subscribe(queue, m -> {
                    slowHandlerEntered.countDown();
                    deliveries.incrementAndGet();
                });
                nodeB.subscribe(queue, m -> {
                    slowHandlerEntered.countDown();
                    deliveries.incrementAndGet();
                });

                messaging.publishTask(queue, newTask("wf-task-once"));

                assertTrue(slowHandlerEntered.await(20, TimeUnit.SECONDS),
                        "the task must be delivered to someone");
                await().atMost(BOUND).until(
                        () -> "COMPLETED".equals(statusOf("maestro_task_queue", "wf-task-once")));

                // Give the loser a chance to double-deliver before asserting.
                await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                        .until(() -> deliveries.get() == 1);
                assertEquals(1, deliveries.get(),
                        "a task must be claimed by exactly one consumer");
            } finally {
                nodeB.close();
            }
        }

        @Test
        @DisplayName("a stale PROCESSING claim is reclaimed, so a crashed consumer does not strand the task")
        void staleProcessingClaimIsReclaimed() throws Exception {
            var queue = taskQueueName();

            // Publish, then simulate a consumer that claimed the row and died.
            messaging.publishTask(queue, newTask("wf-task-stale"));
            ageProcessingClaim("maestro_task_queue", "wf-task-stale");

            var received = new ConcurrentLinkedQueue<TaskMessage>();
            messaging.subscribe(queue, received::add);

            await().atMost(BOUND).until(() -> !received.isEmpty());
            assertEquals("wf-task-stale", received.peek().workflowId());
        }
    }

    @Nested
    @DisplayName("signal channel")
    class SignalChannelTests {

        @Test
        @DisplayName("a published signal is delivered to the service's subscriber")
        void publishedSignalIsDelivered() throws SQLException {
            var service = "svc-" + unique();
            var received = new ConcurrentLinkedQueue<SignalMessage>();
            messaging.subscribeSignals(service, received::add);

            messaging.publishSignal(service, new SignalMessage("wf-sig-1", "approval", null));

            await().atMost(BOUND).until(() -> !received.isEmpty());
            assertEquals("approval", received.peek().signalName());
            assertEquals("wf-sig-1", received.peek().workflowId());
            await().atMost(BOUND).until(
                    () -> "COMPLETED".equals(statusOf("maestro_signal_queue", "wf-sig-1")));
        }

        @Test
        @DisplayName("a signal published for another service is not delivered here")
        void signalsAreRoutedByService() {
            var received = new ConcurrentLinkedQueue<SignalMessage>();
            messaging.subscribeSignals("svc-mine-" + unique(), received::add);

            messaging.publishSignal("svc-theirs-" + unique(),
                    new SignalMessage("wf-sig-other", "approval", null));

            // Nothing should arrive; wait long enough for a poll cycle to pass.
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                    .until(received::isEmpty);
        }
    }

    @Nested
    @DisplayName("lifecycle events")
    class LifecycleEventTests {

        @Test
        @DisplayName("a published lifecycle event is persisted with its type and workflow")
        void lifecycleEventIsPersisted() throws SQLException {
            var event = new WorkflowLifecycleEvent(
                    UUID.randomUUID(), "wf-life-1", "SomeWorkflow", "svc", "default",
                    LifecycleEventType.WORKFLOW_STARTED, null, null, Instant.now());

            messaging.publishLifecycleEvent(event);

            // The lifecycle table is an append-only outbox — it has no status
            // column, unlike the task and signal queues.
            assertEquals(1, rowCount("maestro_lifecycle_event_queue", "wf-life-1"));
            assertEquals("WORKFLOW_STARTED",
                    columnOf("maestro_lifecycle_event_queue", "event_type", "wf-life-1"));
            assertNotNull(columnOf("maestro_lifecycle_event_queue", "workflow_type", "wf-life-1"));
        }
    }

    @Nested
    @DisplayName("handler failure")
    class HandlerFailureTests {

        @Test
        @DisplayName("current behaviour: a throwing handler marks the row FAILED and it is never retried")
        void throwingHandlerMarksRowFailedTerminally() throws Exception {
            var service = "svc-fail-" + unique();
            var attempts = new AtomicInteger();
            messaging.subscribeSignals(service, m -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("handler blew up");
            });

            messaging.publishSignal(service, new SignalMessage("wf-sig-fail", "approval", null));

            await().atMost(BOUND).until(
                    () -> "FAILED".equals(statusOf("maestro_signal_queue", "wf-sig-fail")));

            // FAILED is terminal: the claim query only picks up PENDING rows and
            // stale PROCESSING rows, so this signal is never delivered again.
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                    .until(() -> attempts.get() == 1);
            assertEquals(1, attempts.get(),
                    "today the message is consumed exactly once even though it failed");
            assertEquals("FAILED", statusOf("maestro_signal_queue", "wf-sig-fail"));
        }

        @Test
        @Disabled("known defect: the transport acks on handler failure, losing the signal — "
                + "the row is marked FAILED and never redelivered. Desired behaviour is "
                + "bounded redelivery then a dead-letter, without poison-message loops. "
                + "See tasks/todo.md and docs/test-plan.md §P1/§P3.")
        @DisplayName("desired behaviour: a failed handler must not lose the signal — it is redelivered")
        void failedHandlerMustNotLoseTheSignal() throws Exception {
            var service = "svc-redeliver-" + unique();
            var attempts = new AtomicInteger();
            messaging.subscribeSignals(service, m -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient handler failure");
                }
            });

            messaging.publishSignal(service, new SignalMessage("wf-sig-redeliver", "approval", null));

            // A signal is durable state, not a best-effort notification: a
            // handler that fails must not be the last word on it.
            await().atMost(BOUND).until(() -> attempts.get() >= 2);
            await().atMost(BOUND).until(
                    () -> "COMPLETED".equals(statusOf("maestro_signal_queue", "wf-sig-redeliver")));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private String currentTaskQueue;

    private String taskQueueName() {
        if (currentTaskQueue == null) {
            currentTaskQueue = "queue-" + unique();
        }
        return currentTaskQueue;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static TaskMessage newTask(String workflowId) {
        return new TaskMessage(UUID.randomUUID(), workflowId, "SomeWorkflow",
                UUID.randomUUID(), "svc", null);
    }
}
