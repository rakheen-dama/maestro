package io.b2mash.maestro.samples.loan.verification.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.loan.verification.domain.ProviderOutcome;

/**
 * Activities calling the (simulated) third-party verification provider.
 *
 * <p>Behind an interface so tests can substitute a fake — the same pattern as
 * the other samples' provider activities.
 */
@Activity
public interface VerificationProviderActivities {

    /**
     * Calls the verification provider for the given loan and type.
     *
     * <p>A declined verification is a business outcome and is returned as
     * {@code approved=false} — it is <em>not</em> an exception. Transient
     * provider outages are thrown as {@link RuntimeException} and recovered
     * by the activity's retry policy.
     *
     * @param loanId the loan application id
     * @param type   the verification type
     * @param amount the requested loan amount
     * @return the provider outcome
     */
    ProviderOutcome callProvider(String loanId, String type, long amount);
}
