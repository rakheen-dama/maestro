package io.b2mash.maestro.samples.loan.application.activity.impl;

import io.b2mash.maestro.samples.loan.application.activity.LoanActivities;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo implementation that validates the application and keeps it in memory.
 */
@Component
public class InMemoryLoanActivities implements LoanActivities {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryLoanActivities.class);

    private final Map<String, LoanApplication> applications = new ConcurrentHashMap<>();

    @Override
    public void recordApplication(LoanApplication application) {
        if (application.applicationId() == null || application.applicationId().isBlank()) {
            throw new IllegalArgumentException("applicationId must not be blank");
        }
        if (application.borrowerIds() == null || application.borrowerIds().isEmpty()
                || application.borrowerIds().size() > 2) {
            throw new IllegalArgumentException("borrowerIds must contain 1 or 2 borrowers");
        }
        if (application.amount() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (application.income() <= 0) {
            throw new IllegalArgumentException("income must be positive");
        }
        if (application.requiredDocs() == null || application.requiredDocs().isEmpty()) {
            throw new IllegalArgumentException("requiredDocs must not be empty");
        }

        applications.put(application.applicationId(), application);
        logger.info("Recorded loan application {} (amount={}, borrowers={})",
                application.applicationId(), application.amount(), application.borrowerIds());
    }
}
