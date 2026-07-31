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
 * @param sampleTimeout        loan doc/sign/decision timeout (env {@code MAESTRO_SAMPLE_*})
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
        Duration gateTimeout,
        Duration wakeRecheckInterval,
        Map<String, String> jarPaths,
        Path evidenceRoot) {

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

        return new ChaosConfig(
                mode,
                seed,
                durationMinutes,
                ratePerMinute,
                Duration.ofSeconds(longProp("maestro.chaos.drainSeconds", 120)),
                Duration.ofSeconds(longProp("maestro.chaos.metricsWindowSeconds", 15)),
                Duration.ofSeconds(longProp("maestro.chaos.sampleTimeoutSeconds", 120)),
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
}
