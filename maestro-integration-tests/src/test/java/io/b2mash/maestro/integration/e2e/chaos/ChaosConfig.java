package io.b2mash.maestro.integration.e2e.chaos;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;

/**
 * Immutable configuration for one chaos/soak run, resolved from system
 * properties (the {@code maestro.chaos.*} namespace) with the design's approved
 * starting constants (chaos-harness-design.md §3/§5/§6/§9, ruling Q9) as
 * defaults.
 *
 * <p>Every random decision derives from {@link #seed()}; a run prints and
 * embeds the seed so it can be reproduced. Absent an explicit seed, one is
 * drawn from {@link SecureRandom} at construction.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable record; safe to share across the driver, controller, sampler and
 * checker threads.
 *
 * @param mode                 run mode
 * @param seed                 master PRNG seed (reproduces the decision sequence)
 * @param durationMinutes      workload-generation / chaos window in minutes
 * @param ratePerMinute        Poisson arrival rate (workflows per minute)
 * @param drainSla             {@code T_drain}: grace after heal-all before verify
 * @param metricsWindow        metrics sampler snapshot interval
 * @param sampleTimeout        loan doc/sign timeout (env {@code MAESTRO_SAMPLE_DOC/SIGN_TIMEOUT})
 * @param decisionTimeout      loan decision + underwriter timeout (env
 *                             {@code MAESTRO_SAMPLE_DECISION/UNDERWRITER_TIMEOUT}); see
 *                             {@link #DEFAULT_DECISION_TIMEOUT_SECONDS} for its derivation
 * @param gateTimeout          withdrawal-gate timeout (kept tiny so gates resolve fast)
 * @param wakeRecheckInterval  {@code maestro.signal.wake-recheck-interval} for nodes
 * @param jarPaths             role-key → boot jar path for the three services
 * @param evidenceRoot         directory under which the per-run evidence dir is created
 */
public record ChaosConfig(
        ChaosMode mode,
        long seed,
        int durationMinutes,
        int ratePerMinute,
        Duration drainSla,
        Duration metricsWindow,
        Duration sampleTimeout,
        Duration decisionTimeout,
        Duration gateTimeout,
        Duration wakeRecheckInterval,
        Map<String, String> jarPaths,
        Path evidenceRoot) {

    /**
     * Default decision timeout (Issue 91): the loan's {@code verification.result}
     * and {@code underwriting.decision} awaits must outlast the worst stacked
     * outage the schedule can put between a request and its answer, or a late
     * — not lost — result fails a HAPPY loan. Derived from the schedule and node
     * settings, one outage per side plus one shared action:
     * <ul>
     *   <li>answering node KILL9ed: its run is adopted at most lock TTL 30 s +
     *       recovery poll 60 s = 90 s later (a PAUSE_RESUME is at most 75 s);</li>
     *   <li>loan consumer of the result partition KILL9ed: the partition stays
     *       with the dead member for the Kafka session timeout (45 s) after a
     *       10–30 s heal, then the group rejoins — about 90 s;</li>
     *   <li>one PARTITION (at most 40 s) or BACKEND_OUTAGE (at most 30 s);</li>
     *   <li>provider latency (at most 8 s) + timer poll (5 s) + wake recheck (5 s).</li>
     * </ul>
     * That is about 240 s; 300 s leaves a minute of margin. The two observed
     * late results arrived 128 s and 144 s after their await began. A result
     * that never arrives still times out the loan, and I1 still reports it.
     */
    static final long DEFAULT_DECISION_TIMEOUT_SECONDS = 300;

    /**
     * Builds a configuration from {@code -Dmaestro.chaos.*} system properties.
     *
     * @param defaultMode mode to use when {@code maestro.chaos.mode} is unset
     * @return the resolved configuration
     */
    public static ChaosConfig fromSystemProperties(ChaosMode defaultMode) {
        ChaosMode mode = resolveMode(defaultMode);
        long seed = longProp("maestro.chaos.seed", new SecureRandom().nextLong());

        int durationMinutes;
        int ratePerMinute;
        switch (mode) {
            case SOAK -> {
                durationMinutes = intProp("maestro.chaos.durationMinutes", 120);
                ratePerMinute = intProp("maestro.chaos.ratePerMinute", 20);
            }
            case GOLDEN -> {
                durationMinutes = intProp("maestro.chaos.durationMinutes", 0);
                ratePerMinute = intProp("maestro.chaos.ratePerMinute", 0);
            }
            default -> {
                durationMinutes = intProp("maestro.chaos.durationMinutes", 6);
                ratePerMinute = intProp("maestro.chaos.ratePerMinute", 12);
            }
        }

        Map<String, String> jars = Map.of(
                "loan-application", requireJar("loan-application"),
                "verification-gateway", requireJar("verification-gateway"),
                "underwriting", requireJar("underwriting"));

        Path evidenceRoot = Path.of(System.getProperty(
                "maestro.chaos.evidenceDir",
                System.getProperty("user.dir") + "/build/chaos-evidence"));

        long decisionSecs = longProp("maestro.chaos.decisionTimeoutSeconds",
                DEFAULT_DECISION_TIMEOUT_SECONDS);
        return new ChaosConfig(
                mode,
                seed,
                durationMinutes,
                ratePerMinute,
                // Decision timeout + 120s (240s when it was 120s), calibrated up
                // from the design's 120s starting value (§14.7): a SIGNAL_TIMEOUT
                // submitted at the end of generation spawns an underwriting child
                // whose double-timeout terminal (underwriter timeout + 0:30 senior
                // + processing) lands ~35s past the underwriter timeout after the
                // child starts — observed missing a 120s drain by 2 seconds (run
                // 20260731-222409) when that timeout was 2:00.
                Duration.ofSeconds(longProp("maestro.chaos.drainSeconds", decisionSecs + 120)),
                Duration.ofSeconds(longProp("maestro.chaos.metricsWindowSeconds", 15)),
                Duration.ofSeconds(longProp("maestro.chaos.sampleTimeoutSeconds", 120)),
                Duration.ofSeconds(decisionSecs),
                Duration.ofSeconds(longProp("maestro.chaos.gateTimeoutSeconds", 1)),
                Duration.ofSeconds(longProp("maestro.chaos.wakeRecheckSeconds", 5)),
                jars,
                evidenceRoot);
    }

    private static ChaosMode resolveMode(ChaosMode defaultMode) {
        if (Boolean.getBoolean("maestro.chaos.golden")) {
            return ChaosMode.GOLDEN;
        }
        if (Boolean.getBoolean("maestro.chaos.soak")) {
            return ChaosMode.SOAK;
        }
        String explicit = System.getProperty("maestro.chaos.mode");
        return explicit == null ? defaultMode : ChaosMode.valueOf(explicit.toUpperCase());
    }

    private static String requireJar(String key) {
        String v = System.getProperty("maestro.chaos.jar." + key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "Missing -Dmaestro.chaos.jar." + key + " — run via the e2eTest Gradle "
                    + "task so the loan sample boot jars are built and wired.");
        }
        return v;
    }

    private static long longProp(String key, long dflt) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? dflt : Long.parseLong(v.trim());
    }

    private static int intProp(String key, int dflt) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
    }

    /** @return the sample timeout as an ISO-8601 duration string for env binding. */
    public String sampleTimeoutIso() {
        return sampleTimeout.toString();
    }

    /** @return the decision timeout as an ISO-8601 duration string for env binding. */
    public String decisionTimeoutIso() {
        return decisionTimeout.toString();
    }
}
