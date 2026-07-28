package io.b2mash.maestro.samples.loan.underwriting.controller;

import io.b2mash.maestro.samples.loan.underwriting.domain.Decision;
import io.b2mash.maestro.samples.loan.underwriting.domain.PendingReview;
import io.b2mash.maestro.samples.loan.underwriting.queue.PendingReviewRegistry;
import io.b2mash.maestro.spring.client.MaestroClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Thin REST layer for human underwriting decisions. Each endpoint just
 * converts the request body into a {@link Decision} signal for
 * {@code underwriting-{loanId}-round{n}} — the workflow owns all logic.
 *
 * <p>Both endpoints deliver a {@link Decision} payload (decision-as-payload
 * idiom): the verdict is data, the signal name only identifies the decision
 * point (underwriter desk vs. senior desk after escalation).
 */
@RestController
@RequestMapping("/underwriting")
public class UnderwritingController {

    private final MaestroClient maestro;
    private final PendingReviewRegistry registry;

    public UnderwritingController(MaestroClient maestro, PendingReviewRegistry registry) {
        this.maestro = maestro;
        this.registry = registry;
    }

    /** Underwriter decision for a review round. */
    @PostMapping("/{loanId}/rounds/{round}/decision")
    public ResponseEntity<Map<String, String>> underwriterDecision(
            @PathVariable String loanId,
            @PathVariable int round,
            @RequestBody Decision decision
    ) {
        return signal(loanId, round, "underwriter.decision", decision);
    }

    /** Senior underwriter decision, valid after an escalation. */
    @PostMapping("/{loanId}/rounds/{round}/senior-decision")
    public ResponseEntity<Map<String, String>> seniorDecision(
            @PathVariable String loanId,
            @PathVariable int round,
            @RequestBody Decision decision
    ) {
        return signal(loanId, round, "senior.decision", decision);
    }

    /** Optional nicety: review rounds currently waiting on a human. */
    @GetMapping("/pending")
    public List<PendingReview> pending() {
        return registry.pending();
    }

    private ResponseEntity<Map<String, String>> signal(String loanId, int round,
                                                       String signalName, Decision decision) {
        var workflowId = "underwriting-%s-round%d".formatted(loanId, round);
        maestro.getWorkflow(workflowId).signal(signalName, decision);
        return ResponseEntity.accepted().body(Map.of(
                "workflowId", workflowId,
                "signal", signalName,
                "verdict", decision.verdict().name()
        ));
    }
}
