package io.b2mash.maestro.test;

import io.b2mash.maestro.core.spi.LockHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
}
