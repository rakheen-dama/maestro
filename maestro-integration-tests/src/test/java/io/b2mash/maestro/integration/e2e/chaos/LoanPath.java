package io.b2mash.maestro.integration.e2e.chaos;

/**
 * The four workload path scripts and their declared expectations
 * (chaos-harness-design.md §3). The path <em>declares</em> its expected terminal
 * outcome at submit time so the ledger — not inference — is the invariant
 * checker's source of truth.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable enum; safe to share.
 */
public enum LoanPath {

    /** Human-review approval → two signatures → funded. Gaps {9,18} (gates 1+2). */
    HAPPY(40, "COMPLETED", "FUNDED", false, 2),

    /** Conditions on round 1, extra doc, approval on round 2 → funded. Gaps {9,23}. */
    CONDITIONS_LOOP(20, "COMPLETED", "FUNDED", false, 2),

    /** Approve, reach rate-lock reservation, withdraw at gate #2 → compensated FAILED. Gap {9}. */
    SAGA_WITHDRAWAL(20, "FAILED", null, true, 1),

    /** Never answer underwriting → the round rejects → FAILED. Gap {9}. */
    SIGNAL_TIMEOUT(20, "FAILED", null, false, 1);

    private final int weight;
    private final String expectedTerminal;
    private final String expectedOutput;
    private final boolean compensationExpected;
    private final int maxEventLogGaps;

    LoanPath(int weight, String expectedTerminal, String expectedOutput,
             boolean compensationExpected, int maxEventLogGaps) {
        this.weight = weight;
        this.expectedTerminal = expectedTerminal;
        this.expectedOutput = expectedOutput;
        this.compensationExpected = compensationExpected;
        this.maxEventLogGaps = maxEventLogGaps;
    }

    /**
     * @return the calibrated maximum number of event-log sequence gaps for this
     *         path (golden-run calibration §14.2). A count above this is an
     *         I3(d) anomaly; positions may shift under chaos but the count is
     *         bounded by the path's timed-out-gate structure.
     */
    public int maxEventLogGaps() {
        return maxEventLogGaps;
    }

    /** @return relative arrival weight (percent). */
    public int weight() {
        return weight;
    }

    /** @return expected engine terminal status ({@code COMPLETED} or {@code FAILED}). */
    public String expectedTerminal() {
        return expectedTerminal;
    }

    /** @return expected {@code output.status} ({@code FUNDED}) or {@code null}. */
    public String expectedOutput() {
        return expectedOutput;
    }

    /** @return whether a rate-lock compensation is expected (SAGA_WITHDRAWAL). */
    public boolean compensationExpected() {
        return compensationExpected;
    }

    /**
     * Weighted pick from a uniform draw in {@code [0,100)}.
     *
     * @param roll a value in {@code [0,100)}
     * @return the selected path
     */
    public static LoanPath pick(int roll) {
        int acc = 0;
        for (LoanPath p : values()) {
            acc += p.weight;
            if (roll < acc) {
                return p;
            }
        }
        return HAPPY;
    }
}
