package io.b2mash.maestro.test;

import io.b2mash.maestro.core.exception.LockBackendException;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDistributedLockTest {

    private InMemoryDistributedLock lock;

    @BeforeEach
    void setUp() {
        lock = new InMemoryDistributedLock();
    }

    @Test
    void tryAcquireThenContentionFails() {
        assertTrue(lock.tryAcquire("k", Duration.ofSeconds(30)).isPresent());
        assertTrue(lock.tryAcquire("k", Duration.ofSeconds(30)).isEmpty(),
                "live lock must not be re-acquired");
    }

    @Test
    void tryAcquireSucceedsAfterTtlExpiry() throws InterruptedException {
        var expired = lock.tryAcquire("k", Duration.ofMillis(20)).orElseThrow();
        Thread.sleep(50);

        var reacquired = lock.tryAcquire("k", Duration.ofSeconds(30));
        assertTrue(reacquired.isPresent(), "expired lock must be re-acquirable (crash simulation)");
        assertNotEquals(expired.token(), reacquired.orElseThrow().token());
    }

    @Test
    void renewReturnsTrueForHolder() {
        var handle = lock.tryAcquire("k", Duration.ofSeconds(30)).orElseThrow();

        assertTrue(lock.renew(handle, Duration.ofSeconds(30)));
    }

    @Test
    void renewReturnsFalseForForeignToken() {
        lock.tryAcquire("k", Duration.ofSeconds(30));

        var foreign = new LockHandle("k", "foreign-token", Instant.now().plusSeconds(30));
        assertFalse(lock.renew(foreign, Duration.ofSeconds(30)));
    }

    @Test
    void renewReturnsFalseAfterTtlExpiry() throws InterruptedException {
        var handle = lock.tryAcquire("k", Duration.ofMillis(20)).orElseThrow();
        Thread.sleep(50);

        assertFalse(lock.renew(handle, Duration.ofSeconds(30)),
                "renew of an expired lock must report the lock as lost, matching production backends");
    }

    @Test
    void renewReturnsFalseAfterRelease() {
        var handle = lock.tryAcquire("k", Duration.ofSeconds(30)).orElseThrow();
        lock.release(handle);

        assertFalse(lock.renew(handle, Duration.ofSeconds(30)),
                "renew of a released lock must report the lock as lost");
    }

    @Test
    void renewAllReportsOnlyLostHandlesThroughTheSpiDefault() throws InterruptedException {
        var held = lock.tryAcquire("held", Duration.ofSeconds(30)).orElseThrow();
        var expired = lock.tryAcquire("expired", Duration.ofMillis(20)).orElseThrow();
        lock.tryAcquire("foreign", Duration.ofSeconds(30));
        var foreign = new LockHandle("foreign", "foreign-token", Instant.now().plusSeconds(30));
        Thread.sleep(50);

        var lost = lock.renewAll(List.of(held, expired, foreign), Duration.ofSeconds(30));

        assertEquals(Set.of(expired, foreign), lost,
                "only the expired and the foreign-token handles are lost; the held one is renewed");
        assertTrue(lock.tryAcquire("held", Duration.ofSeconds(30)).isEmpty(), "the held lock must stay held");
    }

    @Test
    void renewAllDefaultAttemptsEveryHandleWhenOneRenewThrows() {
        var backend = new OneKeyFailingLock("b");
        var handles = new ArrayList<LockHandle>();
        for (var key : List.of("a", "b", "c", "d")) {
            handles.add(new LockHandle(key, "t-" + key, Instant.now().plusSeconds(30)));
        }

        var thrown = assertThrows(LockBackendException.class,
                () -> backend.renewAll(handles, Duration.ofSeconds(30)));

        assertEquals("b", thrown.key());
        assertEquals(List.of("a", "b", "c", "d"), List.copyOf(backend.renewCalls),
                "one failing key must not stop the default from renewing every other handle");
    }

    /**
     * A backend may rethrow one shared exception instance (a cached
     * "backend down" exception, say). {@code failure.addSuppressed(failure)}
     * throws {@code IllegalArgumentException}, which would escape the loop and
     * leave every later handle unrenewed.
     */
    @Test
    void renewAllDefaultAttemptsEveryHandleWhenRenewRethrowsOneSharedException() {
        var shared = new LockBackendException("backend down", "b", new RuntimeException());
        var backend = new SharedExceptionLock(Set.of("b", "c"), shared);
        var handles = new ArrayList<LockHandle>();
        for (var key : List.of("a", "b", "c", "d")) {
            handles.add(new LockHandle(key, "t-" + key, Instant.now().plusSeconds(30)));
        }

        RuntimeException thrown = null;
        try {
            backend.renewAll(handles, Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            thrown = e;
        }

        assertEquals(List.of("a", "b", "c", "d"), List.copyOf(backend.renewCalls),
                "a shared exception instance must not stop the default from renewing every other handle");
        assertSame(shared, thrown, "the backend's own exception is rethrown");
        assertEquals(0, shared.getSuppressed().length, "an exception is never suppressed into itself");
    }

    /** Uses the SPI default {@code renewAll}; {@code renew} throws the same instance for every failing key. */
    private static final class SharedExceptionLock implements DistributedLock {

        private final Set<String> failingKeys;
        private final RuntimeException shared;
        final List<String> renewCalls = new ArrayList<>();

        SharedExceptionLock(Set<String> failingKeys, RuntimeException shared) {
            this.failingKeys = failingKeys;
            this.shared = shared;
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            renewCalls.add(handle.key());
            if (failingKeys.contains(handle.key())) {
                throw shared;
            }
            return true;
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }

    /** Uses the SPI default {@code renewAll}; {@code renew} throws for one key and renews the rest. */
    private static final class OneKeyFailingLock implements DistributedLock {

        private final String failingKey;
        final List<String> renewCalls = new ArrayList<>();

        OneKeyFailingLock(String failingKey) {
            this.failingKey = failingKey;
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
            return Optional.empty();
        }

        @Override
        public void release(LockHandle handle) {
        }

        @Override
        public boolean renew(LockHandle handle, Duration ttl) {
            renewCalls.add(handle.key());
            if (handle.key().equals(failingKey)) {
                throw new LockBackendException("simulated failure", handle.key(), new RuntimeException());
            }
            return true;
        }

        @Override
        public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
            return false;
        }
    }
}
