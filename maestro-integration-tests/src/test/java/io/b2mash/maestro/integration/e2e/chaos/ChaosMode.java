package io.b2mash.maestro.integration.e2e.chaos;

/**
 * Run mode for the chaos/soak harness.
 *
 * <p>All three modes share one code path (see {@code chaos-harness-design.md}
 * §8); the mode only changes durations, arrival rate and whether chaos actions
 * fire.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable enum; safe to share.
 */
public enum ChaosMode {

    /** Bounded ~10-minute chaos-active window; the {@code e2eTest} default. */
    PR_GATE,

    /** Hours-long soak (opt-in via {@code -Dmaestro.chaos.soak=true}). */
    SOAK,

    /**
     * Calibration mode: run each path once with <em>no</em> chaos, characterise
     * the per-path event-log gap sets and assert the side-effect log patterns
     * still match (design §5 I3(d), Risk 4). Opt-in via
     * {@code -Dmaestro.chaos.golden=true}.
     */
    GOLDEN
}
