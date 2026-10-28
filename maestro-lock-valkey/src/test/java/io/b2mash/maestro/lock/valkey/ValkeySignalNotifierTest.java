package io.b2mash.maestro.lock.valkey;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ValkeySignalNotifier}.
 *
 * <p>Requires Docker for Testcontainers Valkey instance.
 */
class ValkeySignalNotifierTest extends ValkeyTestSupport {

    private StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private StatefulRedisConnection<String, String> publishConnection;
    private ValkeySignalNotifier notifier;

    @BeforeEach
    void setUpNotifier() {
        pubSubConnection = redisClient.connectPubSub();
        publishConnection = redisClient.connect();
        notifier = new ValkeySignalNotifier(pubSubConnection, publishConnection);
    }

    @AfterEach
    void tearDownNotifier() {
        if (notifier != null) {
            notifier.close();
        }
    }

    @Nested
    @DisplayName("publish and subscribe")
    class PubSubTests {

        @Test
        @DisplayName("subscriber receives published signal")
        void subscribeReceivesSignal() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var receivedSignals = new CopyOnWriteArrayList<String>();

            notifier.subscribe("workflow-1", (wfId, signalName) -> {
                receivedSignals.add(wfId + ":" + signalName);
                latch.countDown();
            });

            notifier.publish("workflow-1", "approval_granted");

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should have been invoked");
            assertEquals(1, receivedSignals.size());
            assertEquals("workflow-1:approval_granted", receivedSignals.getFirst());
        }

        @Test
        @DisplayName("subscribe returns only once the server has registered the subscription — a publish issued immediately after is received")
        void publishImmediatelyAfterSubscribe_isReceived_everyTime() throws InterruptedException {
            for (int i = 0; i < 25; i++) {
                var latch = new CountDownLatch(1);
                var workflowId = "wf-sync-" + i;
                notifier.subscribe(workflowId, (wfId, name) -> latch.countDown());
                notifier.publish(workflowId, "go");
                assertTrue(latch.await(2, TimeUnit.SECONDS), "iteration " + i + ": publish raced ahead of the subscription");
                notifier.unsubscribe(workflowId);
            }
        }

        @Test
        @DisplayName("unsubscribe stops receiving signals")
        void unsubscribeStopsReceiving() throws InterruptedException {
            var receivedSignals = new CopyOnWriteArrayList<String>();

            notifier.subscribe("workflow-2", (wfId, signalName) ->
                    receivedSignals.add(wfId + ":" + signalName));

            Thread.sleep(100);

            // First signal — should be received
            notifier.publish("workflow-2", "signal-1");
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> receivedSignals.size() == 1);

            // Unsubscribe
            notifier.unsubscribe("workflow-2");
            Thread.sleep(100);

            // Second signal — should NOT be received
            notifier.publish("workflow-2", "signal-2");
            Thread.sleep(500);

            assertEquals(1, receivedSignals.size(), "Should not receive signals after unsubscribe");
        }

        @Test
        @DisplayName("multiple subscribers for different workflows")
        void multipleSubscribers() throws InterruptedException {
            var signals1 = new CopyOnWriteArrayList<String>();
            var signals2 = new CopyOnWriteArrayList<String>();

            notifier.subscribe("wf-a", (wfId, signalName) -> signals1.add(signalName));
            notifier.subscribe("wf-b", (wfId, signalName) -> signals2.add(signalName));

            Thread.sleep(100);

            notifier.publish("wf-a", "signal-for-a");
            notifier.publish("wf-b", "signal-for-b");

            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> signals1.size() == 1 && signals2.size() == 1);

            assertEquals("signal-for-a", signals1.getFirst());
            assertEquals("signal-for-b", signals2.getFirst());
        }

        @Test
        @DisplayName("publish with no subscribers is a no-op")
        void publishWithNoSubscribers() {
            // Should not throw
            notifier.publish("no-subscriber-workflow", "some-signal");
        }
    }

    @Nested
    @DisplayName("under a brownout (Issue 32)")
    class BrownoutTests {

        @Test
        @DisplayName("publish against a paused Valkey returns promptly instead of waiting for the 60 s command timeout")
        void publishAgainstPausedValkey_returnsPromptly() throws InterruptedException {
            var docker = valkey.getDockerClient();
            docker.pauseContainerCmd(valkey.getContainerId()).exec();
            try {
                var started = System.nanoTime();
                assertTimeoutPreemptively(Duration.ofSeconds(3),
                        () -> notifier.publish("workflow-paused", "approval"));
                var elapsed = Duration.ofNanos(System.nanoTime() - started);
                assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) < 0,
                        "publish must not wait on a paused server; took " + elapsed);
            } finally {
                docker.unpauseContainerCmd(valkey.getContainerId()).exec();
            }

            // Once the server is back, notifications flow again on the same connection.
            var latch = new CountDownLatch(1);
            notifier.subscribe("workflow-resumed", (wfId, signalName) -> latch.countDown());
            notifier.publish("workflow-resumed", "approval");
            assertTrue(latch.await(5, TimeUnit.SECONDS), "publish must work again after the brownout");
        }
    }
}
