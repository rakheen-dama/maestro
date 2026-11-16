package io.b2mash.maestro.store.postgres.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order in which {@link PostgresWakeListener#close()} and its poll thread
 * release the {@code LISTEN} connection, by construction, on a
 * {@link ScriptedPgDataSource} (no database): no interrupt of a connected
 * poll; only the poll thread touches the connection once {@code start()} has
 * returned; a network timeout, then {@code UNLISTEN *}, then the earlier
 * network timeout restored, then {@code close};
 * a failed session closed without {@code UNLISTEN}; a bounded {@code close()}
 * that never takes a connection whose call is still in flight (Issue 38).
 *
 * <p>The scripted listener joins its poll thread for 300 ms, less than one
 * 500 ms poll, so a {@code close()} can return before the poll thread has
 * released the connection. The ordering tests therefore await the
 * {@code "close"} event before they read the event list.
 *
 * <h2>Thread Safety</h2>
 * <p>Per-test fixtures, confined to the test thread; {@link ScriptedPgDataSource}
 * is thread-safe.
 */
@DisplayName("PostgresWakeListener.close() releases the connection on the poll thread, without an interrupt")
class PostgresWakeListenerCloseOrderTest {

    private final ScriptedPgDataSource scripted = new ScriptedPgDataSource();

    @AfterEach
    void releaseAnyBlockedPoll() {
        scripted.releasePolls();
    }

    @Test
    @DisplayName("close() never interrupts a connected poll")
    void closeNeverInterruptsAConnectedPoll() {
        var listener = scripted.newListener();
        listener.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
        listener.close();
        awaitConnectionClosed();
        assertEquals(List.of(), scripted.events().stream().filter("poll-interrupted"::equals).toList());
    }

    @Test
    @DisplayName("after the last poll the session gets a network timeout, UNLISTEN *, its old timeout back and close, in that order")
    void theSessionIsUnlistenedAfterTheLastPollThenClosed() {
        var listener = scripted.newListener();
        listener.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
        listener.close();
        awaitConnectionClosed();
        var events = scripted.events();
        assertEquals(List.of("setNetworkTimeout 1000", "UNLISTEN *", "setNetworkTimeout 0", "close"),
                events.subList(events.lastIndexOf("poll") + 1, events.size()));
        assertFalse(listener.isConnected());
    }

    @Test
    @DisplayName("close() is terminal: a start() after it borrows no connection and starts no poll")
    void aStartAfterCloseDoesNothing() {
        var listener = scripted.newListener();
        listener.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
        listener.close();
        awaitConnectionClosed();
        var eventsAtClose = scripted.events();

        listener.start();

        assertEquals(eventsAtClose, scripted.events(), "a closed listener must not borrow or LISTEN again");
        assertFalse(listener.isConnected());
    }

    @Test
    @DisplayName("once start() returns, only the poll thread touches the connection")
    void theConnectionIsTouchedOnlyByThePollThreadAfterStart() {
        var listener = scripted.newListener();
        listener.start();                                   // LISTEN runs on this thread, before the poll thread exists
        var fromHere = scripted.events().size();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
        listener.close();
        awaitConnectionClosed();
        assertEquals(Set.of("maestro-pg-wake-listener"),
                Set.copyOf(scripted.eventThreads().subList(fromHere, scripted.eventThreads().size())));
    }

    @Test
    @DisplayName("a poll stuck past the join keeps its connection until it returns, then releases it")
    void aPollThreadStuckPastTheJoinKeepsItsConnectionUntilItReturns() {
        var listener = scripted.newListener();
        listener.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
        scripted.blockPolls();
        await().atMost(Duration.ofSeconds(5)).until(scripted::inBlockedPoll);
        var started = System.nanoTime();
        listener.close();
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0, "close() is bounded");
        assertEquals(List.of(), scripted.events().stream().filter(e -> e.startsWith("UNLISTEN") || e.equals("close")).toList(),
                "nobody may UNLISTEN or close a connection whose poll is still in flight");
        scripted.releasePolls();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.events().contains("close"));
        assertEquals(List.of("setNetworkTimeout 1000", "UNLISTEN *", "setNetworkTimeout 0", "close"),
                scripted.events().subList(scripted.events().size() - 4, scripted.events().size()));
    }

    @Test
    @DisplayName("a session that fails while stopping is closed without UNLISTEN")
    void aSessionThatFailsWhileStoppingIsClosedWithoutUnlisten() {
        var listener = scripted.newListener();
        listener.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
        scripted.blockPolls();
        await().atMost(Duration.ofSeconds(5)).until(scripted::inBlockedPoll);
        scripted.failNextPollWith(() -> new PSQLException("I/O", PSQLState.CONNECTION_FAILURE));
        listener.close();                                   // join times out (300 ms)
        scripted.releasePolls();                            // the in-flight poll now fails
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.events().contains("close"));
        assertEquals(List.of(), scripted.events().stream().filter(e -> e.startsWith("UNLISTEN")).toList());
    }

    @Test
    @DisplayName("a close() during a reconnect back-off returns at once")
    void aCloseDuringABackOffReturnsAtOnce() {
        scripted.refuseConnections(true);
        var listener = scripted.newListener();
        listener.start();                                   // connect fails; the poll thread waits 1 s
        // The poll thread may not have run yet; once its first retry is refused it is in a 2 s back-off.
        await("the poll thread's first refused reconnect").atMost(Duration.ofSeconds(5))
                .until(() -> scripted.refusedConnections() >= 2);
        var started = System.nanoTime();
        listener.close();
        var took = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(took.compareTo(Duration.ofMillis(250)) < 0, "close() during a back-off took " + took.toMillis() + " ms");
    }

    @Test
    @DisplayName("the network timeout is set before UNLISTEN *")
    void theNetworkTimeoutIsSetBeforeUnlisten() {
        var listener = scripted.newListener();
        listener.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
        listener.close();
        awaitConnectionClosed();
        var events = scripted.events();
        var timeout = events.indexOf("setNetworkTimeout 1000");
        assertTrue(timeout >= 0 && timeout < events.indexOf("UNLISTEN *"),
                "setNetworkTimeout 1000 must come before UNLISTEN * — events: " + events);
    }

    /** The 300 ms join can end before a 500 ms poll does; the poll thread releases the connection after it. */
    private void awaitConnectionClosed() {
        await("the scripted connection's close").atMost(Duration.ofSeconds(5))
                .until(() -> scripted.events().contains("close"));
    }
}
