package io.b2mash.maestro.integration.e2e.chaos;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One workload ledger row (chaos-harness-design.md §3): the driver's declared
 * expectation for a single loan workflow, joined against store state by the
 * invariant checker (§5 I1). Written to {@code ledger.jsonl} one line per
 * workflow, flushed immediately (crash-safe).
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable record; safe to share.
 *
 * @param workflowId           {@code loan-<applicationId>}
 * @param applicationId        the loan application id
 * @param path                 the path script
 * @param expectedTerminal     expected engine terminal status
 * @param expectedOutput       expected {@code output.status} or {@code null}
 * @param compensationExpected whether a rate-lock compensation is expected
 * @param borrowerIds          the borrower ids (each must sign)
 * @param submittedAtUtc       ISO-8601 UTC submit time
 * @param scriptCompletedAtUtc ISO-8601 UTC when the script finished driving
 * @param notes                driver notes (unmet effect checks, fallbacks used)
 */
public record LedgerEntry(
        String workflowId,
        String applicationId,
        LoanPath path,
        String expectedTerminal,
        @Nullable String expectedOutput,
        boolean compensationExpected,
        List<String> borrowerIds,
        String submittedAtUtc,
        @Nullable String scriptCompletedAtUtc,
        List<String> notes) {
}
