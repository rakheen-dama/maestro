package io.maestro.test;

import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.LockHandle;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link DistributedLock} for tests.
 *
 * <p>Simple single-JVM lock using {@link ConcurrentHashMap}. No TTL
 * enforcement — tests are short-lived and deterministic. Fencing tokens
 * are generated for correctness of the activity proxy's token validation.
 *
 * <p><b>Thread safety:</b> All operations are thread-safe via
 * {@link ConcurrentHashMap} atomic operations.
 */
public final class InMemoryDistributedLock implements DistributedLock {

    private final ConcurrentHashMap<String, LockHandle> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
        var handle = new LockHandle(key, UUID.randomUUID().toString(), Instant.now().plus(ttl));
        var existing = locks.putIfAbsent(key, handle);
        if (existing != null) {
            return Optional.empty();
        }
        return Optional.of(handle);
    }

    @Override
    public void release(LockHandle handle) {
        locks.computeIfPresent(handle.key(), (_, current) ->
                current.token().equals(handle.token()) ? null : current);
    }

    @Override
    public void renew(LockHandle handle, Duration ttl) {
        locks.computeIfPresent(handle.key(), (_, current) -> {
            if (current.token().equals(handle.token())) {
                return new LockHandle(handle.key(), handle.token(), Instant.now().plus(ttl));
            }
            return current;
        });
    }

    @Override
    public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
        // In single-JVM tests, always succeed as leader
        var handle = new LockHandle(electionKey, candidateId, Instant.now().plus(ttl));
        locks.put(electionKey, handle);
        return true;
    }
}
