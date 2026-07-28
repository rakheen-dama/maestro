package io.b2mash.maestro.spring.config.scanfixture;

/**
 * Activity interface used by {@link ScannedApprovalWorkflow} to prove that
 * classpath-scanned workflow beans still receive {@code @ActivityStub}
 * proxy injection.
 */
public interface ApprovalActivities {

    String approve(String documentId);
}
