package io.b2mash.maestro.store.postgres.lock;

import io.b2mash.maestro.core.exception.LockBackendException;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.LockHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * <h2>Renew all</h2>
 * <p>{@link #renewAll} renews handles in chunks of {@value #RENEW_ALL_CHUNK},
 * one {@code UPDATE ... FROM unnest(keys, tokens) ... RETURNING lock_key} per
 * chunk on a single connection; a handle whose key is not returned was lost.
 * A node renewing thousands of parked workflows' locks thus pays one round
 * trip per chunk instead of one per lock.
 *
 * <h2>Held keys</h2>
 * <p>{@link #heldKeys} answers with one
 * {@code SELECT lock_key ... WHERE lock_key = ANY(keys) AND expires_at > now()}
 * — the keys are one array parameter, so the statement has no bind-count
 * limit. It is advisory: a backend failure answers "unknown" (empty).
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
    private static final String RENEW_ALL_SQL = """
            UPDATE maestro_distributed_lock l
            SET expires_at = now() + make_interval(secs => ?)
            FROM unnest(?::text[], ?::text[]) AS h(lock_key, token)
            WHERE l.lock_key = h.lock_key AND l.token = h.token AND l.expires_at > now()
            RETURNING l.lock_key
            """;

    /** Handles renewed per {@link #RENEW_ALL_SQL} statement. */
    static final int RENEW_ALL_CHUNK = 500;

    // language=SQL
    private static final String HELD_KEYS_SQL = """
            SELECT lock_key FROM maestro_distributed_lock
            WHERE lock_key = ANY(?) AND expires_at > now()
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
            // Transient backend error — propagate so callers can distinguish
            // "backend unavailable" (degrade gracefully) from "lock held
            // elsewhere" (skip the workflow). Returning empty here would be
            // misread as contention.
            throw new LockBackendException("Failed to acquire lock '" + key + "'", key, e);
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
    public boolean renew(LockHandle handle, Duration ttl) {
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
                return true;
            }
            logger.debug("Lock '{}' not renewed — token mismatch or already expired",
                    handle.key());
            return false;
        } catch (SQLException e) {
            // Transient backend error — propagate so callers can retry rather
            // than misreading it as lost ownership
            throw new LockBackendException(
                    "Failed to renew lock '" + handle.key() + "'", handle.key(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>One statement per chunk of {@value #RENEW_ALL_CHUNK} handles, all on
     * one connection in autocommit mode — each chunk commits on its own, so a
     * failure part-way leaves earlier chunks renewed. A {@link SQLException}
     * is thrown as {@link LockBackendException} keyed by the first handle's
     * key; nothing is reported lost.
     */
    @Override
    public Set<LockHandle> renewAll(Collection<LockHandle> handles, Duration ttl) {
        var lost = new HashSet<LockHandle>();
        if (handles.isEmpty()) {
            return lost;
        }
        var all = List.copyOf(handles);
        var ttlSeconds = ttl.toMillis() / 1000.0;

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(RENEW_ALL_SQL)) {

            conn.setAutoCommit(true);
            for (int from = 0; from < all.size(); from += RENEW_ALL_CHUNK) {
                var chunk = all.subList(from, Math.min(from + RENEW_ALL_CHUNK, all.size()));
                var keys = new String[chunk.size()];
                var tokens = new String[chunk.size()];
                for (int i = 0; i < chunk.size(); i++) {
                    keys[i] = chunk.get(i).key();
                    tokens[i] = chunk.get(i).token();
                }
                stmt.setDouble(1, ttlSeconds);
                stmt.setArray(2, conn.createArrayOf("text", keys));
                stmt.setArray(3, conn.createArrayOf("text", tokens));

                var renewedKeys = new HashSet<String>();
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        renewedKeys.add(rs.getString(1));
                    }
                }
                for (var handle : chunk) {
                    if (!renewedKeys.contains(handle.key())) {
                        lost.add(handle);
                    }
                }
            }
        } catch (SQLException e) {
            // Transient backend error — propagate so the caller keeps every
            // handle rather than misreading the failure as lost ownership
            var firstKey = all.getFirst().key();
            throw new LockBackendException(
                    "Failed to renew " + all.size() + " lock(s) (first '" + firstKey + "')", firstKey, e);
        }
        logger.debug("Renewed {} of {} lock(s) for {}ms", all.size() - lost.size(), all.size(), ttl.toMillis());
        return lost;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One {@code SELECT} for the whole collection, on the database clock
     * ({@code expires_at > now()}) — the same clock acquire and renew judge
     * expiry by. A {@link SQLException} is logged and answered with an empty
     * set: the caller then simply tries every key, as it did before this
     * method existed.
     */
    @Override
    public Set<String> heldKeys(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(HELD_KEYS_SQL)) {

            conn.setAutoCommit(true);
            stmt.setArray(1, conn.createArrayOf("text", keys.toArray(String[]::new)));

            var held = new HashSet<String>();
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    held.add(rs.getString(1));
                }
            }
            return held;
        } catch (SQLException e) {
            logger.warn("Could not read which of {} lock(s) are held — treating all as unknown: {}",
                    keys.size(), e.getMessage());
            return Set.of();
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
