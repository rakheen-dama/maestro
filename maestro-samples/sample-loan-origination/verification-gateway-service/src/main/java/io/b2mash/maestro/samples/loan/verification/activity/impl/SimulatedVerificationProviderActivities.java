package io.b2mash.maestro.samples.loan.verification.activity.impl;

import io.b2mash.maestro.samples.loan.verification.activity.VerificationProviderActivities;
import io.b2mash.maestro.samples.loan.verification.domain.ProviderOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulated verification provider with fully deterministic outcomes so the
 * demo scenarios in {@code SPEC.md} are repeatable:
 *
 * <ul>
 *   <li><b>Declined:</b> loan amounts ending in {@code 13}
 *       ({@code amount % 100 == 13}) are declined — a repeatable demo
 *       failure. A decline is a business outcome (returned as
 *       {@code approved=false}), not an exception.</li>
 *   <li><b>Flaky:</b> loan amounts ending in {@code 7}
 *       ({@code amount % 10 == 7}) throw a simulated transient outage on the
 *       first two attempts and succeed from the third. Maestro's activity
 *       retry policy (declared on the workflow's {@code @ActivityStub})
 *       recovers this without any workflow-visible failure.</li>
 *   <li>Everything else is approved on the first attempt.</li>
 * </ul>
 *
 * <p>The per-{@code (loanId, type)} attempt counter is activity-side state —
 * non-determinism is allowed in activity code (only workflow code must be
 * deterministic).
 */
@Component
public class SimulatedVerificationProviderActivities implements VerificationProviderActivities {

    private static final Logger logger =
            LoggerFactory.getLogger(SimulatedVerificationProviderActivities.class);

    private final ConcurrentHashMap<String, AtomicInteger> attemptsByKey = new ConcurrentHashMap<>();

    @Override
    public ProviderOutcome callProvider(String loanId, String type, long amount) {
        var attempt = attemptsByKey
                .computeIfAbsent(key(loanId, type), k -> new AtomicInteger())
                .incrementAndGet();

        if (Math.floorMod(amount, 10) == 7 && attempt <= 2) {
            logger.warn("Simulated transient outage for {} verification of loan {} (attempt {})",
                    type, loanId, attempt);
            throw new RuntimeException(
                    "Simulated provider outage for %s verification of loan %s (attempt %d)"
                            .formatted(type, loanId, attempt));
        }

        if (Math.floorMod(amount, 100) == 13) {
            logger.info("Simulated decline for {} verification of loan {} (amount {} ends in 13)",
                    type, loanId, amount);
            return new ProviderOutcome(false,
                    "%s verification declined (simulated: amount %d ends in 13)"
                            .formatted(type, amount));
        }

        logger.info("Simulated approval for {} verification of loan {} (attempt {})",
                type, loanId, attempt);
        return new ProviderOutcome(true,
                "%s verification passed".formatted(type));
    }

    /**
     * Returns how many provider attempts were made for the given loan and
     * type (test observability).
     */
    public int attempts(String loanId, String type) {
        var counter = attemptsByKey.get(key(loanId, type));
        return counter == null ? 0 : counter.get();
    }

    private static String key(String loanId, String type) {
        return loanId + ":" + type;
    }
}
