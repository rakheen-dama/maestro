package io.b2mash.maestro.test;

import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link DistributedLock} for tests.
 *
 * <p>Simple single-JVM lock using {@link ConcurrentHashMap}. TTL expiry is
 * honoured on {@link #tryAcquire} so crash-recovery scenarios (a "dead"
 * holder's lock becoming re-acquirable) can be simulated. Fencing tokens
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
        var winner = locks.compute(key, (_, current) -> {
            if (current == null || current.expiresAt().isBefore(Instant.now())) {
                return handle;
            }
            return current;
        });
        return winner == handle ? Optional.of(handle) : Optional.empty();
    }

    @Override
    public void release(LockHandle handle) {
        locks.computeIfPresent(handle.key(), (_, current) ->
                current.token().equals(handle.token()) ? null : current);
    }

    @Override
    public boolean renew(LockHandle handle, Duration ttl) {
        var now = Instant.now();
        var renewed = new LockHandle(handle.key(), handle.token(), now.plus(ttl));
        var result = locks.computeIfPresent(handle.key(), (_, current) ->
                current.token().equals(handle.token()) && current.expiresAt().isAfter(now)
                        ? renewed
                        : current);
        return result == renewed;
    }

    @Override
    public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
        // CAS: succeed if no leader exists or this candidate is already leader
        var handle = new LockHandle(electionKey, candidateId, Instant.now().plus(ttl));
        var existing = locks.putIfAbsent(electionKey, handle);
        if (existing == null) {
            return true;
        }
        // Already leader? Renew
        if (existing.token().equals(candidateId)) {
            locks.put(electionKey, handle);
            return true;
        }
        return false;
    }
}
