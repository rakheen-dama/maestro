package io.b2mash.maestro.samples.loan.application.domain;

import java.util.List;

/**
 * Payload of the {@code underwriting.decision} signal (and the Kafka event on
 * {@code loans.underwriting.decisions}).
 *
 * <p>Design idiom (decision-as-payload): one signal name per decision point —
 * the verdict lives in the payload, never in competing signal names.
 *
 * @param loanId     the loan application id
 * @param round      1-based review round the decision belongs to
 * @param verdict    {@code APPROVED}, {@code REJECTED} or {@code CONDITIONS}
 * @param conditions extra document types required when verdict is {@code CONDITIONS}
 */
public record UnderwritingDecision(
        String loanId,
        int round,
        String verdict,
        List<String> conditions
) {}
