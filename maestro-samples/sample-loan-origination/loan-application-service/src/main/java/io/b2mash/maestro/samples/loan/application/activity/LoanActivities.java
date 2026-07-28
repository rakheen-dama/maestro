package io.b2mash.maestro.samples.loan.application.activity;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;

/**
 * Local (non-messaging) activities for the loan application workflow.
 */
@Activity
public interface LoanActivities {

    /**
     * Validates the application and persists demo state.
     *
     * @throws IllegalArgumentException if the application is invalid
     */
    void recordApplication(LoanApplication application);
}
