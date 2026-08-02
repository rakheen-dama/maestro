package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED pin for the round-3 root cause (checker-blindness addendum): the
 * soak-killing interrupter was JUnit's {@code TimeoutExtension} — because
 * {@code -Dmaestro.chaos.soak=true} selected BOTH chaos classes,
 * {@link ChaosPrGateE2EIT} picked up {@code durationMinutes=120} in SOAK mode
 * and ran a 2h generation window into its own 25-minute {@code @Timeout}. The
 * timeout interrupt is what the pre-fix pacer swallowed into the 1.8M-script
 * runaway in soak attempts 1 and 2 (and what the hardened pacer aborted loudly
 * in attempt 3 at seq 503 ≈ 25 min of 20/min pacing).
 *
 * <p>These pins hold the suite-selection contract structurally: each dedicated
 * invocation flag selects ONLY its dedicated class. The complementary halves
 * (PR gate runs on the default invocation; soak class runs under soak=true)
 * are load-bearing in every archived e2eTest/smoke log and are not repeated
 * here — executing them would boot the container cluster.
 */
class ChaosSuiteSelectionTest {

    @Test
    @DisplayName("a soak invocation must not select the PR gate (25-min @Timeout vs 120-min window)")
    void prGate_isExcludedFromSoakInvocations() {
        assertTrue(hasDisabledGuard(ChaosPrGateE2EIT.class, "maestro.chaos.soak", "true"),
                "ChaosPrGateE2EIT must carry @DisabledIfSystemProperty(maestro.chaos.soak=true): "
                + "a soak invocation otherwise runs the PR gate with durationMinutes=120 in "
                + "SOAK mode straight into its 25-min @Timeout — the TimeoutExtension "
                + "interrupt that triggered the pacer runaway of soak attempts 1 and 2");
    }

    @Test
    @DisplayName("golden / boot-smoke / explicit-mode invocations must not select the PR gate either")
    void prGate_isExcludedFromOtherDedicatedInvocations() {
        assertTrue(hasDisabledGuard(ChaosPrGateE2EIT.class, "maestro.chaos.golden", "true"),
                "golden calibration invocations must run only ChaosGoldenRunE2EIT");
        assertTrue(hasDisabledGuard(ChaosPrGateE2EIT.class, "maestro.chaos.smoke", "true"),
                "boot-smoke invocations must run only ClusterBootSmokeIT");
        assertTrue(hasDisabledGuard(ChaosPrGateE2EIT.class, "maestro.chaos.mode", "(?i)(soak|golden)"),
                "an explicit -Dmaestro.chaos.mode=soak|golden must not run the PR gate "
                + "into its 25-min @Timeout (same collision via the alternate spelling)");
    }

    @Test
    @DisplayName("the dedicated suites stay opt-in via their system-property flags")
    void dedicatedSuites_remainOptIn() {
        assertTrue(hasEnabledGuard(ChaosSoakE2EIT.class, "maestro.chaos.soak", "true"));
        assertTrue(hasEnabledGuard(ChaosGoldenRunE2EIT.class, "maestro.chaos.golden", "true"));
        assertTrue(hasEnabledGuard(ClusterBootSmokeIT.class, "maestro.chaos.smoke", "true"));
    }

    private static boolean hasDisabledGuard(Class<?> testClass, String named, String matches) {
        return Arrays.stream(testClass.getAnnotationsByType(DisabledIfSystemProperty.class))
                .anyMatch(g -> g.named().equals(named) && g.matches().equals(matches));
    }

    private static boolean hasEnabledGuard(Class<?> testClass, String named, String matches) {
        return Arrays.stream(testClass.getAnnotationsByType(EnabledIfSystemProperty.class))
                .anyMatch(g -> g.named().equals(named) && g.matches().equals(matches));
    }
}
