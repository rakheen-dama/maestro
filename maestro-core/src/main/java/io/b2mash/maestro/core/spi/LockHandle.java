package io.b2mash.maestro.core.spi;

import java.time.Instant;

/**
 * Handle returned when a distributed lock is successfully acquired.
 *
 * <p>The {@link #token()} is a fencing token — a unique value that prevents
 * stale lock holders from operating after their lock expires. Store
 * implementations should validate the fencing token on writes.
 *
 * <p><b>Thread safety:</b> Records are immutable and therefore thread-safe.
 *
 * @param key       the lock key that was acquired
 * @param token     fencing token (unique per acquisition)
 * @param expiresAt when the lock will expire if not renewed
 * @see DistributedLock#tryAcquire(String, java.time.Duration)
 */
public record LockHandle(
        String key,
        String token,
        Instant expiresAt
) {}
