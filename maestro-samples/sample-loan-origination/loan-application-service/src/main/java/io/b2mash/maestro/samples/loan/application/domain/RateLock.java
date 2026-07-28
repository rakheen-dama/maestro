package io.b2mash.maestro.samples.loan.application.domain;

/**
 * Result of reserving an interest rate lock during funding.
 *
 * @param lockId        the reservation id (needed by the saga compensation)
 * @param applicationId the loan application the lock belongs to
 * @param ratePercent   the locked interest rate
 */
public record RateLock(
        String lockId,
        String applicationId,
        double ratePercent
) {}
