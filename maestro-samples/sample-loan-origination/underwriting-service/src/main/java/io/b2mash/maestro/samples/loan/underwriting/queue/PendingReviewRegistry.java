package io.b2mash.maestro.samples.loan.underwriting.queue;

import io.b2mash.maestro.samples.loan.underwriting.domain.PendingReview;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort, in-memory view of review rounds waiting on a human decision.
 * Backs the optional {@code GET /underwriting/pending} endpoint.
 *
 * <p>Populated by the activity implementations as side effects (queued on
 * {@code autoAssess} → HUMAN_REVIEW, flagged on {@code escalate}, removed on
 * {@code publishDecision}). Because memoized activities do not re-execute on
 * replay, entries are not rebuilt after a service restart — the durable
 * source of truth is always the workflow store; this registry is a
 * convenience view only.
 *
 * <p>Thread-safe: state lives in a {@link ConcurrentHashMap}.
 */
@Component
public class PendingReviewRegistry {

    private final Map<String, PendingReview> pending = new ConcurrentHashMap<>();

    /** Records a review round entering the human queue. */
    public void queued(String loanId, int round, double dti) {
        pending.put(key(loanId, round), new PendingReview(loanId, round, dti, false));
    }

    /** Flags a queued review as escalated to a senior underwriter. */
    public void escalated(String loanId, int round) {
        pending.compute(key(loanId, round), (k, existing) -> existing == null
                ? new PendingReview(loanId, round, Double.NaN, true)
                : new PendingReview(existing.loanId(), existing.round(), existing.dti(), true));
    }

    /** Removes a review once its decision has been published. */
    public void resolved(String loanId, int round) {
        pending.remove(key(loanId, round));
    }

    /** Returns whether the given review round has been escalated. */
    public boolean isEscalated(String loanId, int round) {
        var review = pending.get(key(loanId, round));
        return review != null && review.escalated();
    }

    /** Returns all reviews currently waiting on a human decision. */
    public List<PendingReview> pending() {
        return pending.values().stream()
                .sorted(Comparator.comparing(PendingReview::loanId)
                        .thenComparingInt(PendingReview::round))
                .toList();
    }

    private static String key(String loanId, int round) {
        return loanId + ":round" + round;
    }
}
