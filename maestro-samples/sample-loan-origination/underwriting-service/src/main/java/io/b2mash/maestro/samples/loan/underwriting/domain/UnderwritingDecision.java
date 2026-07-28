package io.b2mash.maestro.samples.loan.underwriting.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The published outcome of an underwriting round. Sent to the
 * {@code loans.underwriting.decisions} Kafka topic, where
 * loan-application-service turns it into the {@code underwriting.decision}
 * signal for {@code loan-{loanId}}.
 *
 * <p>The {@code round} always echoes the round from the workflow input so
 * the orchestrator can correlate the decision with the request it sent.
 *
 * <p>For {@link Verdict#CONDITIONS} the list carries the underwriter's
 * conditions; for {@link Verdict#REJECTED} it carries the rejection reason
 * (e.g. {@code "no decision"} after a double timeout).
 *
 * @param loanId     the loan application ID (also the Kafka key)
 * @param round      the 1-based review round this decision belongs to
 * @param verdict    the decision verdict
 * @param conditions conditions (CONDITIONS verdict) or rejection reason (REJECTED)
 */
public record UnderwritingDecision(
        String loanId,
        int round,
        Verdict verdict,
        List<String> conditions
) {

    public UnderwritingDecision(String loanId, int round, Verdict verdict,
                                @Nullable List<String> conditions) {
        this.loanId = loanId;
        this.round = round;
        this.verdict = verdict;
        this.conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
}
