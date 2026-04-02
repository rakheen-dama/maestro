package io.b2mash.maestro.core.spi;

import java.time.Duration;
import java.util.Optional;

/**
 * Distributed lock and leader election SPI.
 *
 * <p>Used for workflow instance locking (preventing concurrent execution
 * of the same workflow) and timer poller leader election. The reference
 * implementation uses Valkey/Redis via Lettuce.
 *
 * <h2>Fencing Tokens</h2>
 * <p>Each acquired lock includes a fencing token ({@link LockHandle#token()}).
 * This token should be validated by downstream operations to prevent
 * stale lock holders from performing work after their lock has expired
 * and been acquired by another caller.
 *
 * <h2>Fallback Behavior</h2>
 * <p>If the distributed lock backend is unavailable, the engine falls
 * back to Postgres advisory locks for locking and unique constraints
 * for deduplication. See the architecture documentation for details.
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe. Multiple virtual threads will
 * call lock methods concurrently.
 *
 * @see LockHandle
 */
public interface DistributedLock {

    /**
     * Attempts to acquire a distributed lock with the given key and TTL.
     *
     * <p>If the lock is already held by another caller, returns
     * {@link Optional#empty()} — it does <b>not</b> block or throw.
     *
     * <p>The returned {@link LockHandle} contains a fencing token and
     * an expiration time. The lock will be automatically released after
     * the TTL expires unless renewed via {@link #renew(LockHandle, Duration)}.
     *
     * @param key the lock key (e.g., {@code "maestro:lock:workflow:order-abc"})
     * @param ttl time-to-live for the lock
     * @return a lock handle if acquired, or empty if the lock is held
     */
    Optional<LockHandle> tryAcquire(String key, Duration ttl);

    /**
     * Releases a previously acquired lock.
     *
     * <p>The lock is only released if the provided handle's
     * {@link LockHandle#token()} matches the current holder. This
     * prevents accidentally releasing a lock that was already expired
     * and re-acquired by another caller.
     *
     * @param handle the lock handle returned by {@link #tryAcquire}
     */
    void release(LockHandle handle);

    /**
     * Renews a lock's TTL.
     *
     * <p>The renewal is only applied if the handle's token still matches
     * the current holder. If the lock has already expired and been
     * re-acquired, the renewal is silently ignored.
     *
     * @param handle the lock handle to renew
     * @param ttl    new time-to-live from now
     */
    void renew(LockHandle handle, Duration ttl);

    /**
     * Attempts to become the leader for the given election key.
     *
     * <p>Uses a compare-and-set operation: if no leader exists (or the
     * current leader's TTL has expired), the candidate becomes the new
     * leader. Returns {@code true} if the candidate is now the leader.
     *
     * <p>Used for timer poller leader election — only one instance per
     * service should poll for due timers.
     *
     * @param electionKey the election key (e.g., {@code "maestro:leader:timer-poller:order-service"})
     * @param candidateId unique identifier for this candidate
     * @param ttl         how long the leadership claim lasts
     * @return {@code true} if this candidate is now the leader
     */
    boolean trySetLeader(String electionKey, String candidateId, Duration ttl);
}
