package io.b2mash.maestro.messaging.rabbitmq;

import io.b2mash.maestro.core.spi.LifecycleEventType;
import io.b2mash.maestro.core.spi.SignalMessage;
import io.b2mash.maestro.core.spi.TaskMessage;
import io.b2mash.maestro.core.spi.WorkflowLifecycleEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RabbitMqWorkflowMessaging} against a real RabbitMQ backend.
 *
 * <p>This module ships in releases but had no tests at all. The suite covers
 * the round trips for tasks, signals, and lifecycle events, plus the
 * redelivery/dead-letter policy required by {@code issue1-design.md}: a
 * failed handler must not lose the message — it is redelivered in-process by
 * the listener container's stateless retry interceptor — and a message that
 * exhausts its attempt budget must be republished to {@code <queue>.dlq}
 * rather than dropped, and remain replayable from there.
 *
 * <p>Delivery is asynchronous (listener containers run on their own
 * threads), so every assertion about delivery is made through Awaitility
 * rather than by assuming synchronous hand-off.
 */
@DisplayName("RabbitMqWorkflowMessaging against a real RabbitMQ backend")
class RabbitMqWorkflowMessagingTest extends RabbitMqTestSupport {

    private static final Duration BOUND = Duration.ofSeconds(20);

    @Nested
    @DisplayName("task queue")
    class TaskQueueTests {

        @Test
        @DisplayName("a published task is delivered to a subscriber")
        void publishedTaskIsDelivered() {
            var queue = "queue-" + unique();
            var received = new ConcurrentLinkedQueue<TaskMessage>();
            messaging.subscribe(queue, received::add);

            messaging.publishTask(queue, newTask("wf-task-1"));

            await().atMost(BOUND).until(() -> !received.isEmpty());
            assertEquals("wf-task-1", received.peek().workflowId());
        }
    }

    @Nested
    @DisplayName("signal channel")
    class SignalChannelTests {

        @Test
        @DisplayName("a published signal is delivered to the service's subscriber")
        void publishedSignalIsDelivered() {
            var service = "svc-" + unique();
            var received = new ConcurrentLinkedQueue<SignalMessage>();
            messaging.subscribeSignals(service, received::add);

            messaging.publishSignal(service, new SignalMessage("wf-sig-1", "approval", null));

            await().atMost(BOUND).until(() -> !received.isEmpty());
            assertEquals("approval", received.peek().signalName());
            assertEquals("wf-sig-1", received.peek().workflowId());
        }

        @Test
        @DisplayName("a signal published for another service is not delivered here")
        void signalsAreRoutedByService() {
            var received = new ConcurrentLinkedQueue<SignalMessage>();
            messaging.subscribeSignals("svc-mine-" + unique(), received::add);

            messaging.publishSignal("svc-theirs-" + unique(),
                    new SignalMessage("wf-sig-other", "approval", null));

            // Nothing should arrive; wait long enough for a delivery to have
            // happened if routing were broken.
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                    .until(received::isEmpty);
        }
    }

    @Nested
    @DisplayName("lifecycle events")
    class LifecycleEventTests {

        @Test
        @DisplayName("a published lifecycle event reaches a subscriber of the admin fanout exchange")
        void lifecycleEventIsPublished() {
            var adminQueue = admin.declareQueue();
            admin.declareBinding(BindingBuilder.bind(adminQueue)
                    .to(new FanoutExchange(RabbitMqWorkflowMessaging.ADMIN_EVENTS_EXCHANGE)));

            var event = new WorkflowLifecycleEvent(
                    UUID.randomUUID(), "wf-life-1", "SomeWorkflow", "svc", "default",
                    LifecycleEventType.WORKFLOW_STARTED, null, null, Instant.now());

            messaging.publishLifecycleEvent(event);

            var received = new AtomicReference<Message>();
            await().atMost(BOUND).until(() -> {
                var msg = rabbitTemplate.receive(adminQueue.getName(), 200);
                if (msg != null) {
                    received.set(msg);
                }
                return msg != null;
            });

            var deserialized = objectMapper.readValue(received.get().getBody(), WorkflowLifecycleEvent.class);
            assertEquals("wf-life-1", deserialized.workflowId());
            assertEquals(LifecycleEventType.WORKFLOW_STARTED, deserialized.eventType());
        }
    }

    @Nested
    @DisplayName("handler failure")
    class HandlerFailureTests {

        /** Tight enough that exhaustion (3 attempts, ~300ms of backoff) comfortably fits {@link #BOUND}. */
        private RabbitMqRedeliveryConfig tightConfig() {
            return new RabbitMqRedeliveryConfig(
                    3, Duration.ofMillis(100), 2.0, Duration.ofMillis(200), "maestro.dead-letter");
        }

        @Test
        @DisplayName("a failed signal handler must not lose the signal — it is redelivered")
        void failedHandlerMustNotLoseTheSignal() {
            var service = "svc-redeliver-" + unique();
            var tight = newMessaging(tightConfig());
            var attempts = new AtomicInteger();
            tight.subscribeSignals(service, m -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient handler failure");
                }
            });

            tight.publishSignal(service, new SignalMessage("wf-sig-redeliver", "approval", null));

            // A signal is durable state, not a best-effort notification: a
            // handler that fails must not be the last word on it.
            await().atMost(BOUND).until(() -> attempts.get() >= 2);
        }

        @Test
        @DisplayName("a failed task handler must not lose the task either — it is redelivered")
        void failedHandlerMustNotLoseTheTask() {
            var queue = "queue-redeliver-" + unique();
            var tight = newMessaging(tightConfig());
            var attempts = new AtomicInteger();
            tight.subscribe(queue, m -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient handler failure");
                }
            });

            tight.publishTask(queue, newTask("wf-task-redeliver"));

            await().atMost(BOUND).until(() -> attempts.get() >= 2);
        }

        @Test
        @DisplayName("a persistently failing handler republishes to <queue>.dlq with exception headers, not dropped")
        void persistentFailureLandsInDeadLetter() {
            var service = "svc-poison-" + unique();
            var tight = newMessaging(tightConfig());
            var attempts = new AtomicInteger();

            tight.subscribeSignals(service, m -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("poison signal — this handler can never process it");
            });

            var signal = new SignalMessage("wf-sig-poison", "approval", null);
            tight.publishSignal(service, signal);

            var dlqName = "maestro.signals." + service + ".dlq";
            var parked = new AtomicReference<Message>();
            await().atMost(BOUND).until(() -> {
                var msg = rabbitTemplate.receive(dlqName, 200);
                if (msg != null) {
                    parked.set(msg);
                }
                return msg != null;
            });

            var body = objectMapper.readValue(parked.get().getBody(), SignalMessage.class);
            assertEquals(signal.workflowId(), body.workflowId(), "the original body must be preserved");
            assertEquals(signal.signalName(), body.signalName());

            var headers = parked.get().getMessageProperties().getHeaders();
            assertNotNull(headers.get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE),
                    "a parked message must record why it was parked");
            assertNotNull(headers.get(RepublishMessageRecoverer.X_EXCEPTION_STACKTRACE));

            assertEquals(3, attempts.get(), "the attempt budget must be spent, not exceeded");

            // Parking is terminal until an operator replays it: a poison
            // message must not become a hot loop on the signal channel.
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                    .until(() -> attempts.get() == 3);
            assertNull(rabbitTemplate.receive(dlqName, 200), "only one copy may be parked");
        }

        @Test
        @DisplayName("a dead-lettered signal can be replayed from <queue>.dlq back onto the source exchange")
        void deadLetteredSignalIsReplayable() {
            var service = "svc-replay-" + unique();
            var tight = newMessaging(tightConfig());
            var handlerFails = new AtomicBoolean(true);
            var delivered = new ConcurrentLinkedQueue<SignalMessage>();

            tight.subscribeSignals(service, m -> {
                if (handlerFails.get()) {
                    throw new IllegalStateException("store unavailable");
                }
                delivered.add(m);
            });

            var signal = new SignalMessage("wf-sig-replay", "approval", null);
            tight.publishSignal(service, signal);

            var dlqName = "maestro.signals." + service + ".dlq";
            var parked = new AtomicReference<Message>();
            await().atMost(BOUND).until(() -> {
                var msg = rabbitTemplate.receive(dlqName, 200);
                if (msg != null) {
                    parked.set(msg);
                }
                return msg != null;
            });

            // The outage is over — an operator (or a shovel) moves the parked
            // message back onto the source exchange with the original routing
            // key, exactly as documented for RabbitMQ replay.
            handlerFails.set(false);
            rabbitTemplate.send(RabbitMqWorkflowMessaging.SIGNALS_EXCHANGE, service, parked.get());

            await().atMost(BOUND).until(() -> !delivered.isEmpty());
            assertEquals("wf-sig-replay", delivered.peek().workflowId());
            assertEquals("approval", delivered.peek().signalName());
            assertNull(rabbitTemplate.receive(dlqName, 200), "a replayed signal must leave the dead-letter queue empty");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static TaskMessage newTask(String workflowId) {
        return new TaskMessage(UUID.randomUUID(), workflowId, "SomeWorkflow",
                UUID.randomUUID(), "svc", null);
    }
}
