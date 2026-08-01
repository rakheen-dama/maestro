package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 10-minute PR-gate chaos suite (chaos-harness-design.md §8) — what
 * {@code ./gradlew :maestro-integration-tests:e2eTest} runs by default. Boots
 * the six-node loan cluster, drives ~72 workflows across the four paths while
 * killing, pausing, partitioning and rolling nodes for six minutes, heals,
 * drains, and asserts every store-level invariant (I1–I5) with actionable
 * dumps. Issue 11 duplicate side-effects and Issue 12 metrics are captured to
 * evidence; unexplained duplicates are surfaced but do not fail the run
 * (ruling Q8).
 *
 * <p>Runs green three consecutive times locally and nightly in CI. The seed is
 * printed and embedded in every artifact; a failure names the seed, the
 * violated invariants and the dump directory.
 *
 * <p><strong>Default invocation ONLY.</strong> This class runs on the plain
 * {@code e2eTest} invocation and is excluded from every dedicated-flag
 * invocation (soak/golden/boot-smoke, and the explicit {@code mode} spelling).
 * Root cause of soak attempts 1 &amp; 2 (checker-blindness investigation,
 * "Interrupter identified" addendum): {@code -Dmaestro.chaos.soak=true} used
 * to select this class too, so it picked up {@code durationMinutes=120} in
 * SOAK mode and ran a 2-hour generation window into its own 25-minute
 * {@code @Timeout} — JUnit's {@code TimeoutExtension} interrupt was the
 * trigger the pre-fix pacer swallowed into the 1.8M-script runaway.
 */
@Tag("e2e")
@DisabledIfSystemProperty(named = "maestro.chaos.soak", matches = "true",
        disabledReason = "soak invocations run only ChaosSoakE2EIT — a 120-min SOAK window "
                + "here collides with the 25-min @Timeout (the attempt-1/2 runaway trigger)")
@DisabledIfSystemProperty(named = "maestro.chaos.golden", matches = "true",
        disabledReason = "golden calibration runs only ChaosGoldenRunE2EIT")
@DisabledIfSystemProperty(named = "maestro.chaos.smoke", matches = "true",
        disabledReason = "boot smoke runs only ClusterBootSmokeIT")
@DisabledIfSystemProperty(named = "maestro.chaos.mode", matches = "(?i)(soak|golden)",
        disabledReason = "explicit non-PR_GATE mode must not run the PR gate into its 25-min @Timeout")
@DisplayName("Chaos PR-gate: 6-node loan cluster stays correct under chaos")
class ChaosPrGateE2EIT {

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    @DisplayName("cluster survives six minutes of chaos with all store invariants intact")
    void prGate_clusterSurvivesChaos_allInvariantsIntact() {
        var config = ChaosConfig.fromSystemProperties(ChaosMode.PR_GATE);
        var result = new ChaosRun(config).execute();
        assertTrue(result.passed(), result::failureMessage);
    }
}
