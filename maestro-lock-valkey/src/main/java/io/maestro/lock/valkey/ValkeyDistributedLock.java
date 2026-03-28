package io.maestro.lock.valkey;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.maestro.core.spi.DistributedLock;
import io.maestro.core.spi.LockHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Valkey/Redis implementation of {@link DistributedLock} using Lettuce.
 *
 * <h2>Lock Token Format</h2>
 * <p>Each lock acquisition generates a unique token:
 * {@code {instanceId}:{threadId}:{uuid}}. The instanceId is fixed per
 * JVM (generated at construction), ensuring that locks survive thread
 * pool recycling while remaining unique across instances.
 *
 * <h2>Lua Scripts</h2>
 * <p>Release and renew operations use Lua scripts for atomic ownership
 * verification. This prevents race conditions where a lock expires and
 * is re-acquired by another instance between a GET check and the
 * subsequent DEL/PEXPIRE.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Lettuce's {@link RedisCommands} interface
 * is thread-safe — it multiplexes commands over a single TCP connection.
 * Virtual threads yield during I/O naturally via Netty.
 *
 * @see DistributedLock
 * @see LockHandle
 */
public final class ValkeyDistributedLock implements DistributedLock {

    private static final Logger logger = LoggerFactory.getLogger(ValkeyDistributedLock.class);

    /**
     * Lua script: Release a lock only if the token matches.
     * Atomic GET + compare + DEL to prevent releasing another holder's lock.
     */
    private static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """;

    /**
     * Lua script: Renew a lock's TTL only if the token matches.
     * Atomic GET + compare + PEXPIRE to prevent extending a stolen lock.
     */
    private static final String RENEW_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            else
                return 0
            end
            """;

    /**
     * Lua script: Leader election with renewal.
     *
     * <p>If the candidate already holds leadership, renew TTL.
     * If no leader exists, claim leadership. Otherwise, return 0.
     */
    private static final String LEADER_ELECTION_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
                return 1
            elseif current == false then
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
                return 1
            else
                return 0
            end
            """;

    private final RedisCommands<String, String> commands;
    private final String instanceId;

    // Cached script SHAs — loaded eagerly at construction, EVAL fallback if evicted
    private volatile String releaseSha;
    private volatile String renewSha;
    private volatile String leaderElectionSha;

    /**
     * Creates a new Valkey distributed lock.
     *
     * @param commands Lettuce synchronous commands (thread-safe)
     */
    public ValkeyDistributedLock(RedisCommands<String, String> commands) {
        this.commands = commands;
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
        loadScripts();
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, Duration ttl) {
        var token = generateToken();
        var args = SetArgs.Builder.nx().px(ttl.toMillis());
        var result = commands.set(key, token, args);

        if ("OK".equals(result)) {
            var handle = new LockHandle(key, token, Instant.now().plus(ttl));
            logger.debug("Acquired lock '{}' with token '{}'", key, token);
            return Optional.of(handle);
        }

        logger.debug("Failed to acquire lock '{}' — already held", key);
        return Optional.empty();
    }

    @Override
    public void release(LockHandle handle) {
        var result = runScript(releaseSha, RELEASE_SCRIPT,
                new String[]{handle.key()},
                handle.token());

        if (result != null && ((Long) result) > 0) {
            logger.debug("Released lock '{}'", handle.key());
        } else {
            logger.debug("Lock '{}' not released — token mismatch or already expired", handle.key());
        }
    }

    @Override
    public void renew(LockHandle handle, Duration ttl) {
        var result = runScript(renewSha, RENEW_SCRIPT,
                new String[]{handle.key()},
                handle.token(), String.valueOf(ttl.toMillis()));

        if (result != null && ((Long) result) > 0) {
            logger.debug("Renewed lock '{}' for {}ms", handle.key(), ttl.toMillis());
        } else {
            logger.debug("Lock '{}' not renewed — token mismatch or already expired", handle.key());
        }
    }

    @Override
    public boolean trySetLeader(String electionKey, String candidateId, Duration ttl) {
        var result = runScript(leaderElectionSha, LEADER_ELECTION_SCRIPT,
                new String[]{electionKey},
                candidateId, String.valueOf(ttl.toMillis()));

        var isLeader = result != null && ((Long) result) > 0;
        if (isLeader) {
            logger.debug("Candidate '{}' is leader for '{}'", candidateId, electionKey);
        } else {
            logger.debug("Candidate '{}' did not win election for '{}'", candidateId, electionKey);
        }
        return isLeader;
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private String generateToken() {
        return instanceId + ":" + Thread.currentThread().threadId() + ":" + UUID.randomUUID();
    }

    private void loadScripts() {
        try {
            releaseSha = commands.scriptLoad(RELEASE_SCRIPT);
            renewSha = commands.scriptLoad(RENEW_SCRIPT);
            leaderElectionSha = commands.scriptLoad(LEADER_ELECTION_SCRIPT);
            logger.debug("Loaded Lua scripts — release={}, renew={}, leader={}",
                    releaseSha, renewSha, leaderElectionSha);
        } catch (Exception e) {
            logger.warn("Failed to preload Lua scripts, will use inline fallback: {}", e.getMessage());
        }
    }

    /**
     * Executes a Lua script via EVALSHA, falling back to inline EVAL if the
     * script has been evicted from the server's script cache (NOSCRIPT).
     *
     * <p>Note: Redis/Valkey Lua execution is atomic and single-threaded on the
     * server side. This is safe and required for compare-and-set operations
     * like ownership-verified release and renewal.
     */
    private Object runScript(String sha, String script, String[] keys, String... args) {
        if (sha != null) {
            try {
                return commands.evalsha(sha, ScriptOutputType.INTEGER, keys, args);
            } catch (Exception e) {
                // NOSCRIPT or other error — fall through to inline execution
                logger.debug("EVALSHA failed for {}, falling back to inline execution: {}",
                        sha, e.getMessage());
            }
        }
        // Inline execution — always works, slightly slower (script parsed each time)
        return commands.eval(script, ScriptOutputType.INTEGER, keys, args);
    }
}
