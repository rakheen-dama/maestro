package io.b2mash.maestro.samples.loan.application.activity.impl;

import io.b2mash.maestro.samples.loan.application.activity.FundingActivities;
import io.b2mash.maestro.samples.loan.application.domain.Disbursement;
import io.b2mash.maestro.samples.loan.application.domain.RateLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulated funding provider. Activities may be nondeterministic internally
 * ({@code UUID.randomUUID()} is fine here) because their results are memoized
 * by the engine — only workflow code must stay deterministic.
 */
@Component
public class SimulatedFundingActivities implements FundingActivities {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedFundingActivities.class);
    private static final double DEMO_RATE_PERCENT = 5.25;

    private final Map<String, RateLock> activeLocks = new ConcurrentHashMap<>();
    private final Map<String, Disbursement> activeDisbursements = new ConcurrentHashMap<>();

    @Override
    public RateLock reserveRateLock(String applicationId, long amount) {
        var lock = new RateLock(UUID.randomUUID().toString(), applicationId, DEMO_RATE_PERCENT);
        activeLocks.put(lock.lockId(), lock);
        logger.info("Reserved rate lock {} for loan {} at {}%", lock.lockId(), applicationId, lock.ratePercent());
        return lock;
    }

    @Override
    public void releaseRateLock(RateLock rateLock) {
        activeLocks.remove(rateLock.lockId());
        logger.info("COMPENSATION: released rate lock {} for loan {}",
                rateLock.lockId(), rateLock.applicationId());
    }

    @Override
    public Disbursement disburse(String applicationId, long amount) {
        var disbursement = new Disbursement(UUID.randomUUID().toString(), applicationId, amount);
        activeDisbursements.put(disbursement.disbursementId(), disbursement);
        logger.info("Disbursed {} for loan {} (disbursementId={})",
                amount, applicationId, disbursement.disbursementId());
        return disbursement;
    }

    @Override
    public void reverseDisbursement(Disbursement disbursement) {
        activeDisbursements.remove(disbursement.disbursementId());
        logger.info("COMPENSATION: reversed disbursement {} for loan {}",
                disbursement.disbursementId(), disbursement.applicationId());
    }
}
