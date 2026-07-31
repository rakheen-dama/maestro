package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
 */
@Tag("e2e")
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
