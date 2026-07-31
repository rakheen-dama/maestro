package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hours-long soak chaos suite (chaos-harness-design.md §8), opt-in via
 * {@code -Dmaestro.chaos.soak=true}. Same code path as the PR-gate suite but
 * with a longer window (default 120 min) and higher arrival rate, so the parked
 * count grows and shrinks naturally — the spread the Issue 12 vs-parked-count
 * curve needs. Excluded from the PR-gate triple; run weekly + on demand in CI
 * (ruling Q5).
 *
 * <p>Exact command line:
 * <pre>
 * ./gradlew :maestro-integration-tests:e2eTest --rerun-tasks \
 *     -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120
 * </pre>
 */
@Tag("e2e")
@EnabledIfSystemProperty(named = "maestro.chaos.soak", matches = "true")
@DisplayName("Chaos soak: hours-long multi-instance correctness + Issue 12 curves")
class ChaosSoakE2EIT {

    @Test
    @Timeout(value = 200, unit = TimeUnit.MINUTES)
    @DisplayName("cluster stays correct across the full soak window")
    void soak_clusterStaysCorrectAcrossFullWindow() {
        var config = ChaosConfig.fromSystemProperties(ChaosMode.SOAK);
        var result = new ChaosRun(config).execute();
        assertTrue(result.passed(), result::failureMessage);
    }
}
