package io.b2mash.maestro.lock.valkey;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins that {@link ValkeySignalNotifier} never blocks a workflow thread for
 * Lettuce's command timeout (60 s by default) during a Valkey brownout
 * (Ruling R37): {@code subscribe} waits for the server's acknowledgement for
 * at most {@code SUBSCRIBE_ACK_TIMEOUT} (5 s) and then falls back to the
 * wake-recheck interval, and {@code unsubscribe} does not wait at all.
 *
 * <p>No container: the pub/sub connection is a Mockito stub whose commands
 * never complete — the brownout, made deterministic. The synchronous command
 * API is stubbed to block too, so an implementation that went back to
 * {@code sync()} would hang here rather than pass.
 */
@DisplayName("ValkeySignalNotifier under a brownout (acknowledgement never arrives)")
class ValkeySignalNotifierAckTimeoutTest {

    private static final String CHANNEL = "maestro:signal:wf-brownout";

    private final CompletableFuture<Void> neverAcknowledged = new CompletableFuture<>();
    private final CountDownLatch never = new CountDownLatch(1);
    private RedisFuture<Void> subscribeFuture;
    private RedisFuture<Void> unsubscribeFuture;
    private ValkeySignalNotifier notifier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        StatefulRedisPubSubConnection<String, String> pubSub = mock(StatefulRedisPubSubConnection.class);
        StatefulRedisConnection<String, String> publish = mock(StatefulRedisConnection.class);
        RedisPubSubAsyncCommands<String, String> async = mock(RedisPubSubAsyncCommands.class);
        RedisPubSubCommands<String, String> sync = mock(RedisPubSubCommands.class);
        subscribeFuture = mock(RedisFuture.class);
        unsubscribeFuture = mock(RedisFuture.class);

        when(pubSub.async()).thenReturn(async);
        when(pubSub.sync()).thenReturn(sync);
        when(async.subscribe(anyString())).thenReturn(subscribeFuture);
        when(async.unsubscribe(anyString())).thenReturn(unsubscribeFuture);
        // A real, never-completing future gives get(timeout) its real semantics.
        when(subscribeFuture.get(anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> neverAcknowledged.get(inv.getArgument(0), inv.getArgument(1)));
        when(subscribeFuture.get()).thenAnswer(inv -> neverAcknowledged.get());
        when(unsubscribeFuture.get(anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> neverAcknowledged.get(inv.getArgument(0), inv.getArgument(1)));
        when(unsubscribeFuture.get()).thenAnswer(inv -> neverAcknowledged.get());
        // The synchronous API would block for the full command timeout.
        Answer<Void> blockForever = inv -> {
            never.await();
            return null;
        };
        doAnswer(blockForever).when(sync).subscribe(anyString());
        doAnswer(blockForever).when(sync).unsubscribe(anyString());

        notifier = new ValkeySignalNotifier(pubSub, publish);
    }

    @Test
    @DisplayName("subscribe whose acknowledgement never arrives returns within ~5 s and leaves no callback registered")
    void unacknowledgedSubscribe_returnsWithinBound_andLeavesNoCallback() {
        var invoked = new AtomicInteger();

        var started = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(8),
                () -> notifier.subscribe("wf-brownout", (wf, name) -> invoked.incrementAndGet()));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(4)) >= 0,
                "subscribe must wait for the acknowledgement (bounded), not return immediately: " + elapsed);
        // The fallback is the wake-recheck interval, so no stale callback may linger.
        notifier.message(CHANNEL, "approval");
        assertEquals(0, invoked.get(), "a timed-out subscribe must not leave its callback registered");
    }

    @Test
    @DisplayName("subscribe whose acknowledgement fails returns normally and leaves no callback registered")
    void failedSubscribe_returnsNormally_andLeavesNoCallback() {
        neverAcknowledged.completeExceptionally(new IllegalStateException("connection reset"));
        var invoked = new AtomicInteger();

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> notifier.subscribe("wf-brownout", (wf, name) -> invoked.incrementAndGet()));

        notifier.message(CHANNEL, "approval");
        assertEquals(0, invoked.get());
    }

    @Test
    @DisplayName("an interrupted subscribe keeps the interrupt flag, returns, and leaves no callback registered")
    void interruptedSubscribe_restoresFlag_andLeavesNoCallback() {
        var invoked = new AtomicInteger();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            Thread.currentThread().interrupt();
            try {
                notifier.subscribe("wf-brownout", (wf, name) -> invoked.incrementAndGet());
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
            } finally {
                Thread.interrupted();
            }
        });

        notifier.message(CHANNEL, "approval");
        assertEquals(0, invoked.get());
    }

    @Test
    @DisplayName("unsubscribe does not wait for the server")
    void unsubscribe_doesNotWait() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> notifier.unsubscribe("wf-brownout"));
    }
}
