# Load-suite run evidence

`wp11/` and `wp12/` hold `result.json` files exactly as `perf/run.sh` wrote
them (and, where a self-test needs it, a `prometheus.json`). They are never
edited after the run: each is the record of what that run measured and how
the evaluator of its harness judged it.

**A stored `checks` array and `verdict` may predate the current gates.** A
file's `checks` and `verdict` are the ones its own harness's evaluator wrote
at run time — for the first run of each WP12 scenario that was a stub
(verdict `ERROR`), and later review rounds changed some gates (S2's statement
gates, S6's janitor and sampler checks, among others). The raw
`measurements` are what is kept; the judgement is re-derived:

- `perf/eval/selftest-evidence.sh` (run on every pull request that touches
  `perf/**`) rebuilds each cited run's `identity.json` and `measurements.json`
  from these files and evaluates them with the **current** evaluator,
  asserting each run's verdict (the WP12 records, and the superseded runs
  too) and the scenario's self-test cases built on it.
- `docs/test-plan.md` § Finding-linked scenarios lists each WP12 run's stored
  verdict next to its re-evaluated one and the commit of the evaluator that
  produced it.

So a gate that changes after a run shows up there and in the self-test, not
as an edit to the run's file.

`perf/lib/evidence-selftest.sh` checks that no file here carries an absolute
path or a host name.
