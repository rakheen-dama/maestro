package io.b2mash.maestro.samples.loan.underwriting.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A human underwriting decision, delivered as a signal payload.
 *
 * <p><b>Decision-as-payload idiom (mandatory design idiom #1):</b> each
 * decision point in the workflow awaits exactly ONE signal name
 * ({@code underwriter.decision}, or {@code senior.decision} after
 * escalation). The verdict — approve, reject, or conditions — travels
 * inside this payload. Approve/reject are never modelled as competing
 * signal names, so the workflow has a single await per decision point and
 * a "losing" signal can never be silently dropped.
 *
 * @param verdict    the decision verdict
 * @param conditions conditions attached to a {@link Verdict#CONDITIONS}
 *                   verdict; empty for other verdicts
 */
public record Decision(Verdict verdict, List<String> conditions) {

    public Decision(Verdict verdict, @Nullable List<String> conditions) {
        this.verdict = verdict;
        this.conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
}
