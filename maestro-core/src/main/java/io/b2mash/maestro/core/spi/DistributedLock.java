package io.b2mash.maestro.core.spi;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Distributed lock and leader election SPI.
 *
 * <p>Used for workflow instance locking (preventing concurrent execution
 * of the same workflow) and timer poller leader election. The reference
 * implementation is the PostgreSQL row-lock backend in
 * {@code maestro-store-postgres}.
 *
 * <h2>Fencing Tokens</h2>
 * <p>Each acquired lock includes a fencing token ({@link LockHandle#token()}).
 * This token should be validated by downstream operations to prevent
 * stale lock holders from performing work after their lock has expired
 * and been acquired by another caller.
 *
 * <h2>Fallback Behavior</h2>
 * <p>If the distributed lock backend is unavailable, the engine proceeds
 * unlocked — the store's unique event constraint and optimistic instance
 * versioning remain the correctness backstop. See the architecture
 * documentation for details.
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
     * the current holder.
     *
     * <p>Implementations should <b>throw</b> on transient backend errors
     * (network, timeout) and reserve {@code false} for a token mismatch —
     * the lock expired and may have been re-acquired by another holder.
     * This lets callers distinguish a retryable hiccup from lost ownership.
     *
     * @param handle the lock handle to renew
     * @param ttl    new time-to-live from now
     * @return {@code true} if the lock was renewed; {@code false} if
     *         ownership has been lost (token no longer matches)
     */
    boolean renew(LockHandle handle, Duration ttl);

    /**
     * Renews many locks' TTLs in one call and reports the ones that were lost.
     *
     * <p>Same per-handle rule as {@link #renew(LockHandle, Duration)}: a handle
     * is renewed only if its token still matches the current, unexpired
     * holder. The engine's instance-lock renewer calls this once per pass
     * with every lock the node holds, so a backend that can renew many rows
     * in one round trip should override it — the default issues one
     * {@link #renew} per handle. The default attempts <em>every</em> handle
     * even after a {@code renew} throws, so one failing key cannot stop the
     * others being renewed; it then rethrows the first exception (later ones
     * attached as suppressed).
     *
     * <p>The two outcomes stay as distinct as they are for {@code renew}:
     * <ul>
     *   <li><b>Returned</b> — the backend answered, and these handles were
     *       <em>not</em> renewed because the lock is no longer held with that
     *       token (expired, re-acquired by another holder, or released).
     *       Ownership of each is lost. Every other handle passed in was
     *       renewed. The returned handles are elements of {@code handles}.</li>
     *   <li><b>Thrown</b> — the backend could not be asked (implementations
     *       throw {@link io.b2mash.maestro.core.exception.LockBackendException};
     *       the default rethrows the first exception {@code renew} threw).
     *       Some handles may have been renewed, but nothing is reported lost:
     *       the caller must treat none as lost. A handle that was genuinely
     *       lost in the same call is therefore only acted on by a later call
     *       that does not throw — the store's lease fence keeps that window
     *       safe.</li>
     * </ul>
     *
     * @param handles the lock handles to renew; may be empty
     * @param ttl     new time-to-live from now, applied to every renewed lock
     * @return the handles whose ownership has been lost; empty if every
     *         handle was renewed
     * @throws io.b2mash.maestro.core.exception.LockBackendException if the
     *         backend could not be asked
     */
    default Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
        var lost = new HashSet<LockHandle>();
        RuntimeException failure = null;
        for (var handle : handles) {
            try {
                if (!renew(handle, ttl)) {
                    lost.add(handle);
                }
            } catch (RuntimeException e) {
                // Keep going: this key's failure must not leave the others
                // unrenewed. Errors are not caught — they always propagate.
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return lost;
    }

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
