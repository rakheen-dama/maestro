package io.b2mash.maestro.lock.postgres;

import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PostgresDistributedLock} against the {@code DistributedLock}
 * SPI contract on a real PostgreSQL backend.
 *
 * <p>This is the default lock backend for the Postgres-only profile, and until
 * now nothing exercised its SQL: the sibling {@link PostgresDistributedLockTest}
 * covers only the backend-failure exception contract using a throwing
 * {@code DataSource}, so acquire/release/renew/leader semantics were unverified.
 *
 * <p>Assertions are written against the SPI's documented contract rather than
 * against the implementation, so a failure here means the implementation is
 * wrong rather than merely different.
 *
 * <p>TTL expiry is simulated by ageing a row's {@code expires_at} rather than by
 * sleeping, keeping the suite fast and deterministic.
 */
@DisplayName("PostgresDistributedLock against a real PostgreSQL backend")
class PostgresDistributedLockContractTest extends PostgresLockTestSupport {

    private static final Duration TTL = Duration.ofSeconds(30);

    @Nested
    @DisplayName("tryAcquire")
    class TryAcquireTests {

        @Test
        @DisplayName("acquires a free lock and returns a handle carrying the key and a token")
        void acquiresFreeLock() {
            var handle = lock.tryAcquire("key-free", TTL);

            assertTrue(handle.isPresent(), "a free lock must be acquirable");
            assertEquals("key-free", handle.get().key());
            assertFalse(handle.get().token().isBlank(), "a handle must carry a fencing token");
            // expires_at is computed by Postgres as now() + ttl, so it is
            // checked against the database clock: comparing it to the JVM clock
            // makes the test hostage to host/container drift, which was
            // observed once on a heavily loaded machine.
            var dbNow = databaseNowUnchecked();
            assertTrue(handle.get().expiresAt().isAfter(dbNow),
                    () -> "handle expires at " + handle.get().expiresAt()
                            + " which is not after database now " + dbNow);

            // Drift tolerance does not weaken the check: the TTL must still
            // come back with the right magnitude, so a timezone or unit
            // conversion error (which would shift it by hours) still fails here.
            var remaining = Duration.between(dbNow, handle.get().expiresAt());
            assertTrue(remaining.compareTo(TTL.plusSeconds(5)) <= 0
                            && remaining.compareTo(TTL.minusSeconds(5)) >= 0,
                    () -> "expiry must be about " + TTL + " away, was " + remaining
                            + " (jvm zone " + java.util.TimeZone.getDefault().getID() + ")");
        }

        @Test
        @DisplayName("returns empty when the lock is already held — it does not block or throw")
        void contendedLockReturnsEmpty() {
            assertTrue(lock.tryAcquire("key-contended", TTL).isPresent());

            assertTrue(lock.tryAcquire("key-contended", TTL).isEmpty(),
                    "a live lock must not be acquirable by a second caller");
        }

        @Test
        @DisplayName("a separate lock instance also loses to a live lock — the lock is distributed")
        void contendedAcrossInstancesReturnsEmpty() {
            var otherNode = new PostgresDistributedLock(newDataSource());
            assertTrue(lock.tryAcquire("key-cross", TTL).isPresent());

            assertTrue(otherNode.tryAcquire("key-cross", TTL).isEmpty(),
                    "ownership must be shared state, not per-instance state");
        }

        @Test
        @DisplayName("an expired lock is re-acquirable and the new holder gets a fresh token")
        void expiredLockIsReacquirable() throws SQLException {
            var first = lock.tryAcquire("key-expiring", TTL).orElseThrow();
            expireLock("key-expiring");

            var second = lock.tryAcquire("key-expiring", TTL);

            assertTrue(second.isPresent(), "an expired lock must be acquirable");
            assertNotEquals(first.token(), second.orElseThrow().token(),
                    "re-acquisition must mint a new fencing token");
            assertEquals(1, lockRowCount("key-expiring"),
                    "re-acquisition must update the row, not duplicate it");
        }

        @Test
        @DisplayName("distinct keys do not contend")
        void distinctKeysDoNotContend() {
            assertTrue(lock.tryAcquire("key-a", TTL).isPresent());
            assertTrue(lock.tryAcquire("key-b", TTL).isPresent());
        }

        @Test
        @DisplayName("exactly one of many concurrent callers wins")
        void concurrentAcquireHasOneWinner() throws InterruptedException {
            int threads = 16;
            var ready = new CountDownLatch(threads);
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            var winners = new AtomicInteger();
            var errors = new ConcurrentLinkedQueue<Throwable>();

            for (int i = 0; i < threads; i++) {
                Thread.ofVirtual().start(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (lock.tryAcquire("key-race", TTL).isPresent()) {
                            winners.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "all racers must start");
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "all racers must finish");

            assertTrue(errors.isEmpty(), "no racer may fail: " + errors);
            assertEquals(1, winners.get(), "exactly one concurrent caller may hold the lock");
        }
    }

    @Nested
    @DisplayName("release")
    class ReleaseTests {

        @Test
        @DisplayName("releases a held lock so another caller can take it")
        void releaseFreesTheLock() {
            var handle = lock.tryAcquire("key-release", TTL).orElseThrow();

            lock.release(handle);

            assertTrue(lock.tryAcquire("key-release", TTL).isPresent(),
                    "a released lock must be immediately acquirable");
        }

        @Test
        @DisplayName("a stale token must not release the current holder's lock")
        void wrongTokenDoesNotRelease() throws SQLException {
            var stale = lock.tryAcquire("key-steal", TTL).orElseThrow();
            expireLock("key-steal");
            var current = lock.tryAcquire("key-steal", TTL).orElseThrow();

            // The previous holder tries to release after silently losing the lock.
            lock.release(stale);

            assertEquals(1, lockRowCount("key-steal"),
                    "a stale token must not delete the new holder's lock");
            assertTrue(lock.tryAcquire("key-steal", TTL).isEmpty(),
                    "the current holder must still hold the lock");
            assertNotEquals(stale.token(), current.token());
        }

        @Test
        @DisplayName("releasing an already-released lock is a no-op")
        void releaseIsIdempotent() {
            var handle = lock.tryAcquire("key-idem", TTL).orElseThrow();

            lock.release(handle);
            lock.release(handle);

            assertTrue(lock.tryAcquire("key-idem", TTL).isPresent());
        }
    }

    @Nested
    @DisplayName("renew")
    class RenewTests {

        @Test
        @DisplayName("the holder can renew a live lock")
        void renewLiveLock() {
            var handle = lock.tryAcquire("key-renew", TTL).orElseThrow();

            assertTrue(lock.renew(handle, TTL), "the holder must be able to renew");
        }

        @Test
        @DisplayName("renewing an expired lock reports the loss instead of resurrecting it")
        void renewExpiredLockReturnsFalse() throws SQLException {
            var handle = lock.tryAcquire("key-renew-exp", TTL).orElseThrow();
            expireLock("key-renew-exp");

            assertFalse(lock.renew(handle, TTL),
                    "an expired lock must not be renewable — the owner has lost it");
        }

        @Test
        @DisplayName("renewing with a stale token does not extend the current holder's lock")
        void renewWithStaleTokenReturnsFalse() throws SQLException {
            var stale = lock.tryAcquire("key-renew-stale", TTL).orElseThrow();
            expireLock("key-renew-stale");
            lock.tryAcquire("key-renew-stale", TTL).orElseThrow();

            assertFalse(lock.renew(stale, TTL), "only the current token holder may renew");
        }

        @Test
        @DisplayName("renewing a lock that never existed returns false")
        void renewUnknownLockReturnsFalse() {
            var phantom = new LockHandle("key-phantom", "no-such-token",
                    Instant.now().plusSeconds(30));

            assertFalse(lock.renew(phantom, TTL));
        }

        @Test
        @DisplayName("a renewed lock stays held past its original expiry")
        void renewExtendsTheTerm() throws SQLException {
            var handle = lock.tryAcquire("key-renew-extend", Duration.ofSeconds(1)).orElseThrow();

            assertTrue(lock.renew(handle, Duration.ofMinutes(5)));

            // Ageing by the ORIGINAL ttl would have expired it had renew not extended.
            assertTrue(lock.tryAcquire("key-renew-extend", TTL).isEmpty(),
                    "a renewed lock must remain held");
        }
    }

    @Nested
    @DisplayName("leader election")
    class LeaderElectionTests {

        @Test
        @DisplayName("the first candidate becomes leader")
        void firstCandidateWins() {
            assertTrue(lock.trySetLeader("election-1", "node-a", TTL));
        }

        @Test
        @DisplayName("a second candidate does not displace a live leader")
        void secondCandidateLoses() {
            assertTrue(lock.trySetLeader("election-2", "node-a", TTL));

            assertFalse(lock.trySetLeader("election-2", "node-b", TTL),
                    "a live leader must not be displaced");
        }

        @Test
        @DisplayName("the sitting leader re-asserts its own leadership to extend its term")
        void leaderCanReassert() {
            assertTrue(lock.trySetLeader("election-3", "node-a", TTL));

            assertTrue(lock.trySetLeader("election-3", "node-a", TTL),
                    "the leader must be able to renew its term — this is how the "
                            + "timer poller stays leader across cycles");
        }

        @Test
        @DisplayName("a new candidate takes over once the leader's term expires")
        void newLeaderAfterExpiry() throws SQLException {
            assertTrue(lock.trySetLeader("election-4", "node-a", TTL));
            expireLeader("election-4");

            assertTrue(lock.trySetLeader("election-4", "node-b", TTL),
                    "an expired term must allow a new leader");
        }

        @Test
        @DisplayName("independent elections do not interfere")
        void independentElections() {
            assertTrue(lock.trySetLeader("election-x", "node-a", TTL));
            assertTrue(lock.trySetLeader("election-y", "node-b", TTL));
        }

        @Test
        @DisplayName("exactly one of many concurrent candidates becomes leader")
        void concurrentElectionHasOneWinner() throws InterruptedException {
            int candidates = 12;
            var ready = new CountDownLatch(candidates);
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(candidates);
            var leaders = new AtomicInteger();

            for (int i = 0; i < candidates; i++) {
                var candidateId = "node-" + i;
                Thread.ofVirtual().start(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (lock.trySetLeader("election-race", candidateId, TTL)) {
                            leaders.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));

            assertEquals(1, leaders.get(), "only one candidate may win a single election");
        }
    }
}
