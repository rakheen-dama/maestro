package io.b2mash.maestro.samples.loan.application.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.Compensate;
import io.b2mash.maestro.samples.loan.application.domain.Disbursement;
import io.b2mash.maestro.samples.loan.application.domain.RateLock;

/**
 * Funding-phase activities with saga compensations.
 *
 * <p>Each forward activity declares its compensation via {@link Compensate}
 * (the same compensation API style as the existing samples). When the
 * {@code @Saga}-annotated workflow fails after a forward step completed, the
 * engine unwinds the compensation stack in reverse (LIFO) order.
 */
@Activity
public interface FundingActivities {

    /** Reserves an interest rate lock. Compensated by {@link #releaseRateLock}. */
    @Compensate("releaseRateLock")
    RateLock reserveRateLock(String applicationId, long amount);

    /** Releases a previously reserved rate lock (saga compensation). */
    void releaseRateLock(RateLock rateLock);

    /** Disburses the loan funds. Compensated by {@link #reverseDisbursement}. */
    @Compensate("reverseDisbursement")
    Disbursement disburse(String applicationId, long amount);

    /** Reverses a disbursement (saga compensation). */
    void reverseDisbursement(Disbursement disbursement);
}
