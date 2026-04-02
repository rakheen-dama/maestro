package io.b2mash.maestro.lock.postgres;

import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link DistributedLock}.
 *
 * <h2>Locking Strategy</h2>
 * <p>Uses two tables created by the Flyway migration
 * {@code V100__maestro_lock_postgres.sql}:
 * <ul>
 *   <li>{@code maestro_distributed_lock} — row-level locks with TTL-based expiry</li>
 *   <li>{@code maestro_leader_election} — leader election with TTL-based expiry</li>
 * </ul>
 *
 * <h2>Acquire</h2>
 * <p>{@code INSERT ... ON CONFLICT DO UPDATE} where the existing row's
 * {@code expires_at} has passed. This atomically acquires a free or expired
 * lock and rejects contention against a live lock.
 *
 * <h2>Release</h2>
 * <p>{@code DELETE WHERE lock_key = ? AND token = ?} — idempotent; only
 * the holder (matching fencing token) can release.
 *
 * <h2>Renew</h2>
 * <p>{@code UPDATE SET expires_at WHERE lock_key = ? AND token = ?} — if
 * the lock was already lost (expired and re-acquired), zero rows are
 * affected and the renewal is silently ignored.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each operation obtains its own JDBC
 * connection from the {@link DataSource} connection pool.
 *
 * @see DistributedLock
 * @see LockHandle
 */
public final class PostgresDistributedLock implements DistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(PostgresDistributedLock.class);

    // language=SQL
    private static final String ACQUIRE_SQL = """
            INSERT INTO maestro_distributed_lock (lock_key, token, expires_at)
            VALUES (?, ?, now() + make_interval(secs => ?))
            ON CONFLICT (lock_key) DO UPDATE
                SET token      = EXCLUDED.token,
                    expires_at = EXCLUDED.expires_at,
                    acquired_at = now()
                WHERE maestro_distributed_lock.expires_at < now()
            RETURNING expires_at
            """;

    // language=SQL
    private static final String RELEASE_SQL = """
            DELETE FROM maestro_distributed_lock
            WHERE lock_key = ? AND token = ?
            """;

    // language=SQL
    private static final String RENEW_SQL = """
            UPDATE maestro_distributed_lock
            SET expires_at = now() + make_interval(secs => ?)
            WHERE lock_key = ? AND token = ? AND expires_at > now()
            """;

    // language=SQL
    private static final String LEADER_ELECTION_SQL = """
            INSERT INTO maestro_leader_election (election_key, candidate_id, expires_at)
            VALUES (?, ?, now() + make_interval(secs => ?))
            ON CONFLICT (election_key) DO UPDATE
                SET candidate_id = EXCLUDED.candidate_id,
                    expires_at   = EXCLUDED.expires_at,
                    acquired_at  = now()
                WHERE maestro_leader_election.expires_at < now()
                   OR maestro_leader_election.candidate_id = EXCLUDED.candidate_id
            RETURNING election_key
            """;

    private final DataSource dataSource;
    private final String instanceId;

    /**
     * Creates a new Postgres distributed lock.
     *
     * @param dataSource the JDBC DataSource for obtaining connections
     */
    public PostgresDistributedLock(DataSource dataSource) {
        this.dataSource = dataSource;
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
        var token = generateToken();
        var ttlSeconds = ttl.toMillis() / 1000.0;

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(ACQUIRE_SQL)) {

            conn.setAutoCommit(true);
            stmt.setString(1, key);
            stmt.setString(2, token);
            stmt.setDouble(3, ttlSeconds);

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    var expiresAt = rs.getTimestamp(1).toInstant();
                    var handle = new LockHandle(key, token, expiresAt);
                    logger.debug("Acquired lock '{}' with token '{}'", key, token);
                    return Optional.of(handle);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to acquire lock '{}': {}", key, e.getMessage());
            return Optional.empty();
        }

        logger.debug("Failed to acquire lock '{}' — already held", key);
        return Optional.empty();
    }

    @Override
    public void release(LockHandle handle) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(RELEASE_SQL)) {

            conn.setAutoCommit(true);
            stmt.setString(1, handle.key());
            stmt.setString(2, handle.token());

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logger.debug("Released lock '{}'", handle.key());
            } else {
                logger.debug("Lock '{}' not released — token mismatch or already expired",
                        handle.key());
            }
        } catch (SQLException e) {
            logger.warn("Failed to release lock '{}': {}", handle.key(), e.getMessage());
        }
    }

    @Override
    public void renew(LockHandle handle, Duration ttl) {
        var ttlSeconds = ttl.toMillis() / 1000.0;

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(RENEW_SQL)) {

            conn.setAutoCommit(true);
            stmt.setDouble(1, ttlSeconds);
            stmt.setString(2, handle.key());
            stmt.setString(3, handle.token());

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logger.debug("Renewed lock '{}' for {}ms", handle.key(), ttl.toMillis());
            } else {
                // Silently ignore — lock was lost (expired and possibly re-acquired)
                logger.debug("Lock '{}' not renewed — token mismatch or already expired",
                        handle.key());
            }
        } catch (SQLException e) {
            logger.warn("Failed to renew lock '{}': {}", handle.key(), e.getMessage());
        }
    }

    @Override
    public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
        var ttlSeconds = ttl.toMillis() / 1000.0;

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(LEADER_ELECTION_SQL)) {

            conn.setAutoCommit(true);
            stmt.setString(1, electionKey);
            stmt.setString(2, candidateId);
            stmt.setDouble(3, ttlSeconds);

            try (var rs = stmt.executeQuery()) {
                var isLeader = rs.next();
                if (isLeader) {
                    logger.debug("Candidate '{}' is leader for '{}'", candidateId, electionKey);
                } else {
                    logger.debug("Candidate '{}' did not win election for '{}'",
                            candidateId, electionKey);
                }
                return isLeader;
            }
        } catch (SQLException e) {
            logger.warn("Leader election failed for '{}': {}", electionKey, e.getMessage());
            return false;
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private String generateToken() {
        return instanceId + ":" + Thread.currentThread().threadId() + ":" + UUID.randomUUID();
    }
}
