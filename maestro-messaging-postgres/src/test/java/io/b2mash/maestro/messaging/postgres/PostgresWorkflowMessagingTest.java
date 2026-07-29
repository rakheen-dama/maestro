package io.b2mash.maestro.messaging.postgres;

import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
        @DisplayName("a persistently failing handler parks the signal in DEAD_LETTER after a bounded number of attempts")
        void persistentFailureLandsInDeadLetter() throws Exception {
            var service = "svc-poison-" + unique();
            var attempts = new AtomicInteger();

            try (var tight = newMessaging(new PostgresRedeliveryConfig(
                    3, Duration.ofMillis(100), 2.0, Duration.ofMillis(200)))) {
                tight.subscribeSignals(service, m -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("poison signal — this handler can never process it");
                });

                tight.publishSignal(service, new SignalMessage("wf-sig-poison", "approval", null));

                await().atMost(BOUND).until(
                        () -> "DEAD_LETTER".equals(statusOf("maestro_signal_queue", "wf-sig-poison")));
                assertEquals(3, intColumnOf("maestro_signal_queue", "attempts", "wf-sig-poison"),
                        "the attempt budget must be spent, not exceeded");
                assertNotNull(columnOf("maestro_signal_queue", "last_error", "wf-sig-poison"),
                        "a parked message must record why it was parked");

                // Parking is terminal until an operator replays it: a poison
                // message must not become a hot loop on the signal channel.
                await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                        .until(() -> attempts.get() == 3);
            }
        }

        @Test
        @DisplayName("a dead-lettered signal is listable and replay delivers it")
        void deadLetteredSignalIsListableAndReplayable() throws Exception {
            var service = "svc-replay-" + unique();
            var handlerFails = new AtomicBoolean(true);
            var delivered = new AtomicInteger();

            try (var tight = newMessaging(new PostgresRedeliveryConfig(
                    2, Duration.ofMillis(100), 2.0, Duration.ofMillis(200)))) {
                tight.subscribeSignals(service, m -> {
                    if (handlerFails.get()) {
                        throw new IllegalStateException("store unavailable");
                    }
                    delivered.incrementAndGet();
                });

                tight.publishSignal(service, new SignalMessage("wf-sig-replay", "approval", null));
                await().atMost(BOUND).until(
                        () -> "DEAD_LETTER".equals(statusOf("maestro_signal_queue", "wf-sig-replay")));

                var parked = tight.listDeadLetterSignals(service, 10);
                assertEquals(1, parked.size(), "the parked signal must be inspectable");
                var message = parked.getFirst();
                assertEquals("wf-sig-replay", message.workflowId());
                assertEquals("approval", message.name());
                assertEquals(2, message.attempts());
                assertNotNull(message.lastError());

                // The outage is over — an operator replays the parked signal.
                handlerFails.set(false);
                assertTrue(tight.replaySignal(message.id()), "a DEAD_LETTER row must be replayable");

                await().atMost(BOUND).until(() -> delivered.get() == 1);
                await().atMost(BOUND).until(
                        () -> "COMPLETED".equals(statusOf("maestro_signal_queue", "wf-sig-replay")));
                assertTrue(tight.listDeadLetterSignals(service, 10).isEmpty(),
                        "a replayed signal must leave the dead-letter listing");
            }
        }

        @Test
        @DisplayName("a failed task handler must not lose the task either — it is redelivered")
        void failedHandlerMustNotLoseTheTask() throws Exception {
            var queue = taskQueueName();
            var attempts = new AtomicInteger();
            messaging.subscribe(queue, m -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient handler failure");
                }
            });

            messaging.publishTask(queue, newTask("wf-task-redeliver"));

            await().atMost(BOUND).until(() -> attempts.get() >= 2);
            await().atMost(BOUND).until(
                    () -> "COMPLETED".equals(statusOf("maestro_task_queue", "wf-task-redeliver")));
        }

        @Test
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
