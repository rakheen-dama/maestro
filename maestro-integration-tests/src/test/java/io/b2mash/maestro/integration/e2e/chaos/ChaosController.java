package io.b2mash.maestro.integration.e2e.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single-threaded chaos scheduler (chaos-harness-design.md §4). Holds the
 * Docker client (via {@link ChaosCluster}) and drives one blocking action at a
 * time — each action performs its fault, waits a seeded duration, then runs its
 * own recovery step to completion before the next action is chosen. Because the
 * scheduler is single-threaded and every action recovers before returning, at
 * most one node is unavailable at any instant, so the safety rule "at least one
 * un-harassed node per service" holds structurally; {@code ROLLING_RESTART}
 * additionally waits for node A healthy before touching node B.
 *
 * <p>All randomness derives from a {@link SplittableRandom} split off the master
 * seed, so a seed reproduces the exact decision sequence. Every action appends a
 * flushed line to {@code chaos-actions.jsonl} carrying the harassment start and
 * recovery timestamps against which duplicate side effects are correlated (§7).
 * The controller guarantees at least two {@code PAUSE_RESUME} actions against a
 * loan-application node per run (the Issue 11 split-brain trigger) by forcing the
 * first two actions.
 *
 * <h2>Thread Safety</h2>
 * <p>Confined to the single controller thread; the action log writer serialises
 * its own appends.
 */
public final class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ChaosCluster cluster;
    private final ChaosMode mode;
    private final SplittableRandom rng;
    private final EvidenceWriter.JsonlWriter actionLog;
    private final AtomicInteger seq = new AtomicInteger();
    private final long startMonotonic = System.nanoTime();

    private int pauseResumeLoanCount;

    /**
     * @param cluster  the live cluster
     * @param mode     run mode (sizes the inter-action gaps)
     * @param evidence evidence writer (opens {@code chaos-actions.jsonl})
     * @param rng      the controller's PRNG split
     */
    public ChaosController(ChaosCluster cluster, ChaosMode mode, EvidenceWriter evidence,
                           SplittableRandom rng) {
        this.cluster = cluster;
        this.mode = mode;
        this.rng = rng;
        this.actionLog = evidence.openJsonl("chaos-actions.jsonl");
    }

    private enum ActionType {
        KILL9(25), PAUSE_RESUME(25), PARTITION(20), BACKEND_OUTAGE(15), ROLLING_RESTART(15);

        final int weight;

        ActionType(int weight) {
            this.weight = weight;
        }
    }

    /**
     * Runs the chaos schedule for {@code durationMinutes}, blocking. On return,
     * nothing is harassed (the last action recovered before the loop exited).
     *
     * @param durationMinutes chaos window
     */
    public void run(int durationMinutes) {
        long end = System.nanoTime() + Duration.ofMinutes(durationMinutes).toNanos();
        log.info("[chaos] controller starts ({} min window)", durationMinutes);
        int iteration = 0;
        while (System.nanoTime() < end) {
            sleep(gapMillis());
            if (System.nanoTime() >= end) {
                break;
            }
            // Guarantee >=2 PAUSE_RESUME on loan nodes (Issue 11 data): force the
            // first two actions deterministically from the seed.
            if (iteration == 0) {
                pauseResume(NodeRole.LOAN_A);
            } else if (iteration == 1) {
                pauseResume(NodeRole.LOAN_B);
            } else {
                dispatch(chooseAction());
            }
            iteration++;
        }
        // If the window was too short to force both, top up now.
        while (pauseResumeLoanCount < 2) {
            pauseResume(pauseResumeLoanCount == 0 ? NodeRole.LOAN_A : NodeRole.LOAN_B);
        }
        log.info("[chaos] controller done: {} actions, {} loan pause-resumes",
                seq.get(), pauseResumeLoanCount);
    }

    /** Closes the action log. */
    public void close() {
        actionLog.close();
    }

    // ---------------------------------------------------------------- dispatch

    private ActionType chooseAction() {
        int total = 0;
        for (ActionType a : ActionType.values()) {
            total += a.weight;
        }
        int roll = rng.nextInt(total);
        int acc = 0;
        for (ActionType a : ActionType.values()) {
            acc += a.weight;
            if (roll < acc) {
                return a;
            }
        }
        return ActionType.KILL9;
    }

    private void dispatch(ActionType type) {
        switch (type) {
            case KILL9 -> kill9(randomNode());
            case PAUSE_RESUME -> pauseResume(randomNode());
            case PARTITION -> partition(randomNode());
            case BACKEND_OUTAGE -> backendOutage(randomBackend());
            case ROLLING_RESTART -> rollingRestart(randomService());
        }
    }

    // ----------------------------------------------------------------- actions

    private void kill9(NodeRole node) {
        long started = System.nanoTime();
        String startTs = nowUtc();
        cluster.kill(node);
        sleep(rangeMillis(10, 30));
        String healStart = nowUtc();
        cluster.replace(node);
        record("KILL9", node.name(), Map.of(), startTs, healStart, nowUtc(), ms(started));
    }

    private void pauseResume(NodeRole node) {
        long started = System.nanoTime();
        String startTs = nowUtc();
        long pauseSecs = rangeSecs(45, 75);   // > 1.5x the 30s instance-lock TTL
        cluster.pause(node);
        sleep(pauseSecs * 1000);
        String healStart = nowUtc();
        cluster.unpause(node);
        record("PAUSE_RESUME", node.name(), Map.of("pauseSecs", pauseSecs),
                startTs, healStart, nowUtc(), ms(started));
        if (node.service() == NodeRole.Service.LOAN_APPLICATION) {
            pauseResumeLoanCount++;
        }
    }

    private void partition(NodeRole node) {
        long started = System.nanoTime();
        String startTs = nowUtc();
        long secs = rangeSecs(10, 40);
        cluster.partition(node);
        sleep(secs * 1000);
        String healStart = nowUtc();
        cluster.reconnect(node);
        record("PARTITION", node.name(), Map.of("secs", secs), startTs, healStart, nowUtc(), ms(started));
    }

    private void backendOutage(String backend) {
        long started = System.nanoTime();
        String startTs = nowUtc();
        long secs = rangeSecs(10, 30);        // bounded <=30s, non-overlapping (single-threaded)
        cluster.pauseBackend(backend);
        sleep(secs * 1000);
        String healStart = nowUtc();
        cluster.unpauseBackend(backend);
        record("BACKEND_OUTAGE", backend, Map.of("secs", secs), startTs, healStart, nowUtc(), ms(started));
    }

    private void rollingRestart(NodeRole.Service service) {
        long started = System.nanoTime();
        String startTs = nowUtc();
        for (NodeRole node : service.roles()) {
            cluster.stopGraceful(node);       // grace 40s > shutdown timeout 30s
            cluster.replace(node);            // wait healthy before the next node
        }
        record("ROLLING_RESTART", service.name(), Map.of(), startTs, startTs, nowUtc(), ms(started));
    }

    // ------------------------------------------------------------------ seeded

    private NodeRole randomNode() {
        return NodeRole.values()[rng.nextInt(NodeRole.values().length)];
    }

    private NodeRole.Service randomService() {
        return NodeRole.Service.values()[rng.nextInt(NodeRole.Service.values().length)];
    }

    private String randomBackend() {
        return List.of("postgres", "kafka", "valkey").get(rng.nextInt(3));
    }

    private long gapMillis() {
        return mode == ChaosMode.SOAK ? rangeMillis(20, 60) : rangeMillis(15, 45);
    }

    private long rangeMillis(int loSecs, int hiSecs) {
        return rangeSecs(loSecs, hiSecs) * 1000;
    }

    private long rangeSecs(int lo, int hi) {
        return lo + rng.nextInt(hi - lo + 1);
    }

    private void record(String action, String target, Map<String, Object> params,
                        String startedTs, String healStartedTs, String healedTs, long monotonicMs) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("seq", seq.incrementAndGet());
        entry.put("startedTs", startedTs);
        entry.put("monotonicMs", monotonicMs);
        entry.put("action", action);
        entry.put("target", target);
        entry.put("params", params);
        entry.put("healStartedTs", healStartedTs);
        entry.put("healedTs", healedTs);
        actionLog.append(entry);
        log.info("[chaos] action#{} {} {} started={} healed={}", entry.get("seq"), action, target,
                startedTs, healedTs);
    }

    private long ms(long startNanos) {
        return (System.nanoTime() - startMonotonic) / 1_000_000;
    }

    private static String nowUtc() {
        return ISO.format(Instant.now());
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
