package io.maestro.lock.valkey;

import io.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ValkeyDistributedLock}.
 *
 * <p>Requires Docker for Testcontainers Valkey instance.
 */
class ValkeyDistributedLockTest extends ValkeyTestSupport {

    private ValkeyDistributedLock lock;

    @BeforeEach
    void setUpLock() {
        lock = new ValkeyDistributedLock(commands);
    }

    // ── tryAcquire ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("tryAcquire")
    class TryAcquireTests {

        @Test
        @DisplayName("returns handle with key, token, and future expiresAt")
        void acquireReturnsHandle() {
            var handle = lock.tryAcquire("test:lock:1", Duration.ofSeconds(10));

            assertTrue(handle.isPresent());
            assertEquals("test:lock:1", handle.get().key());
            assertNotNull(handle.get().token());
            assertFalse(handle.get().token().isEmpty());
            assertTrue(handle.get().expiresAt().isAfter(Instant.now()));
        }

        @Test
        @DisplayName("returns empty when lock is already held")
        void acquireSameKeyFails() {
            var first = lock.tryAcquire("test:lock:1", Duration.ofSeconds(10));
            var second = lock.tryAcquire("test:lock:1", Duration.ofSeconds(10));

            assertTrue(first.isPresent());
            assertTrue(second.isEmpty());
        }

        @Test
        @DisplayName("different keys can be acquired independently")
        void acquireDifferentKeys() {
            var a = lock.tryAcquire("test:lock:a", Duration.ofSeconds(10));
            var b = lock.tryAcquire("test:lock:b", Duration.ofSeconds(10));

            assertTrue(a.isPresent());
            assertTrue(b.isPresent());
        }

        @Test
        @DisplayName("succeeds after TTL expiry")
        void acquireAfterExpiry() {
            lock.tryAcquire("test:lock:1", Duration.ofMillis(100));

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                var handle = lock.tryAcquire("test:lock:1", Duration.ofSeconds(10));
                assertTrue(handle.isPresent());
            });
        }

        @Test
        @DisplayName("concurrent acquire — only one wins")
        void concurrentAcquire() throws InterruptedException {
            var key = "test:lock:concurrent";
            var results = Collections.synchronizedList(new ArrayList<LockHandle>());
            var latch = new CountDownLatch(10);

            for (int i = 0; i < 10; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        var handle = lock.tryAcquire(key, Duration.ofSeconds(10));
                        handle.ifPresent(results::add);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1, results.size(), "Exactly one thread should acquire the lock");
        }

        @Test
        @DisplayName("token contains instance identifier")
        void tokenContainsInstanceId() {
            var handle = lock.tryAcquire("test:lock:1", Duration.ofSeconds(10));

            assertTrue(handle.isPresent());
            var token = handle.get().token();
            // Token format: {instanceId}:{threadId}:{uuid}
            var parts = token.split(":");
            assertTrue(parts.length >= 3, "Token should have at least 3 colon-separated parts");
        }
    }

    // ── release ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("release")
    class ReleaseTests {

        @Test
        @DisplayName("releases lock with correct token — key deleted")
        void releaseWithCorrectToken() {
            var handle = lock.tryAcquire("test:lock:1", Duration.ofSeconds(10)).orElseThrow();

            lock.release(handle);

            // Key should be gone
            var reacquired = lock.tryAcquire("test:lock:1", Duration.ofSeconds(10));
            assertTrue(reacquired.isPresent());
        }

        @Test
        @DisplayName("does NOT release lock with wrong token — key preserved")
        void releaseWithWrongToken() {
            lock.tryAcquire("test:lock:1", Duration.ofSeconds(10));

            // Create a fake handle with wrong token
            var fakeHandle = new LockHandle("test:lock:1", "wrong-token", Instant.now().plusSeconds(10));
            lock.release(fakeHandle);

            // Lock should still be held — cannot re-acquire
            var result = lock.tryAcquire("test:lock:1", Duration.ofSeconds(10));
            assertTrue(result.isEmpty(), "Lock should still be held after release with wrong token");
        }

        @Test
        @DisplayName("release after key expired is a no-op")
        void releaseAfterExpiry() {
            var handle = lock.tryAcquire("test:lock:1", Duration.ofMillis(100)).orElseThrow();

            await().atMost(2, TimeUnit.SECONDS).until(() -> commands.get("test:lock:1") == null);

            // Should not throw
            lock.release(handle);
        }
    }

    // ── renew ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("renew")
    class RenewTests {

        @Test
        @DisplayName("extends TTL with correct token")
        void renewExtendsttl() {
            var handle = lock.tryAcquire("test:lock:1", Duration.ofMillis(500)).orElseThrow();

            // Renew with a much longer TTL
            lock.renew(handle, Duration.ofSeconds(30));

            // Wait past original TTL — lock should still be held
            await().pollDelay(600, TimeUnit.MILLISECONDS)
                    .atMost(2, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        var pttl = commands.pttl("test:lock:1");
                        assertTrue(pttl > 1000, "Lock TTL should be extended beyond original");
                    });
        }

        @Test
        @DisplayName("does NOT renew with wrong token")
        void renewWithWrongToken() {
            lock.tryAcquire("test:lock:1", Duration.ofMillis(500));

            var fakeHandle = new LockHandle("test:lock:1", "wrong-token", Instant.now().plusSeconds(10));
            lock.renew(fakeHandle, Duration.ofSeconds(30));

            // Original TTL should be unchanged (short) — lock expires soon
            await().atMost(2, TimeUnit.SECONDS).until(() -> commands.get("test:lock:1") == null);
        }

        @Test
        @DisplayName("renew after key expired is a no-op")
        void renewAfterExpiry() {
            var handle = lock.tryAcquire("test:lock:1", Duration.ofMillis(100)).orElseThrow();

            await().atMost(2, TimeUnit.SECONDS).until(() -> commands.get("test:lock:1") == null);

            // Should not throw
            lock.renew(handle, Duration.ofSeconds(30));

            // Key should still be absent
            assertFalse(commands.exists("test:lock:1") > 0);
        }
    }

    // ── trySetLeader ────────────────────────────────────────────────────

    @Nested
    @DisplayName("trySetLeader")
    class LeaderElectionTests {

        @Test
        @DisplayName("first candidate becomes leader")
        void firstCandidateWins() {
            var result = lock.trySetLeader("test:leader:1", "candidate-a", Duration.ofSeconds(10));

            assertTrue(result);
            assertEquals("candidate-a", commands.get("test:leader:1"));
        }

        @Test
        @DisplayName("same candidate re-election renews TTL")
        void sameCandidateRenews() {
            lock.trySetLeader("test:leader:1", "candidate-a", Duration.ofMillis(500));

            // Re-elect with longer TTL
            var result = lock.trySetLeader("test:leader:1", "candidate-a", Duration.ofSeconds(30));

            assertTrue(result);
            var pttl = commands.pttl("test:leader:1");
            assertTrue(pttl > 1000, "TTL should be renewed to the new value");
        }

        @Test
        @DisplayName("different candidate while leader held returns false")
        void differentCandidateBlocked() {
            lock.trySetLeader("test:leader:1", "candidate-a", Duration.ofSeconds(10));

            var result = lock.trySetLeader("test:leader:1", "candidate-b", Duration.ofSeconds(10));

            assertFalse(result);
            assertEquals("candidate-a", commands.get("test:leader:1"));
        }

        @Test
        @DisplayName("different candidate succeeds after TTL expiry")
        void differentCandidateAfterExpiry() {
            lock.trySetLeader("test:leader:1", "candidate-a", Duration.ofMillis(100));

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                var result = lock.trySetLeader("test:leader:1", "candidate-b", Duration.ofSeconds(10));
                assertTrue(result);
            });

            assertEquals("candidate-b", commands.get("test:leader:1"));
        }

        @Test
        @DisplayName("concurrent election — only one winner")
        void concurrentElection() throws InterruptedException {
            var key = "test:leader:concurrent";
            var winners = Collections.synchronizedList(new ArrayList<String>());
            var latch = new CountDownLatch(10);

            for (int i = 0; i < 10; i++) {
                var candidateId = "candidate-" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        if (lock.trySetLeader(key, candidateId, Duration.ofSeconds(10))) {
                            winners.add(candidateId);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1, winners.size(), "Exactly one candidate should win the election");
        }
    }

    // ── TTL expiration ──────────────────────────────────────────────────

    @Nested
    @DisplayName("TTL expiration")
    class TtlTests {

        @Test
        @DisplayName("lock expires after TTL")
        void lockExpires() {
            lock.tryAcquire("test:lock:ttl", Duration.ofMillis(200));

            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> commands.get("test:lock:ttl") == null);
        }

        @Test
        @DisplayName("leader election TTL expiry allows new leader")
        void leaderElectionExpiry() {
            lock.trySetLeader("test:leader:ttl", "old-leader", Duration.ofMillis(200));

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertTrue(lock.trySetLeader("test:leader:ttl", "new-leader", Duration.ofSeconds(10)));
            });
        }
    }
}
