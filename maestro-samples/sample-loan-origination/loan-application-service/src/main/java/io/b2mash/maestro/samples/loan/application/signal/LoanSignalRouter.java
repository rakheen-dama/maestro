package io.b2mash.maestro.samples.loan.application.signal;

import io.b2mash.maestro.samples.loan.application.domain.UnderwritingDecision;
import io.b2mash.maestro.samples.loan.application.domain.VerificationResult;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.springframework.stereotype.Component;

/**
 * Routes cross-service Kafka events into the {@code loan-{loanId}} workflow
 * as signals. Signals are persisted before delivery, so events arriving
 * before the workflow reaches its await point (or even before it exists —
 * orphan adoption) are never lost.
 */
@Component
public class LoanSignalRouter {

    @MaestroSignalListener(topic = "loans.verification.results", signalName = "verification.result")
    public SignalRouting routeVerificationResult(VerificationResult event) {
        return SignalRouting.builder()
                .workflowId("loan-" + event.loanId())
                .payload(event)
                .build();
    }

    @MaestroSignalListener(topic = "loans.underwriting.decisions", signalName = "underwriting.decision")
    public SignalRouting routeUnderwritingDecision(UnderwritingDecision event) {
        return SignalRouting.builder()
                .workflowId("loan-" + event.loanId())
                .payload(event)
                .build();
    }
}
