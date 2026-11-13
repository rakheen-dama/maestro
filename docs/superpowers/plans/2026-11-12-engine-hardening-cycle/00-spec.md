# Engine-hardening cycle — spec

Written 2026-11-12 against `main` @ `cbf5a2a`. Status: **approved by the
maintainer 2026-11-12, with every default in §7 (D1–D8) and every
recommendation (D9–D11) accepted as written.**

## 1. Intent

The host-safety handover (`tasks/handover-2026-11-12-after-host-safety-cycle.md`)
left a list of engine, host-impact and follow-up findings from audits at an
older commit, most never executed. The maintainer asked for a team to address
everything remaining except the Maven Central release, and to add k6
performance/load tests to the test suite.

Four Opus researchers re-verified every finding against current `main` on
2026-11-12. Their reports are in `evidence/` and are the source for every
claim here; each work package's planner must read the relevant report in full.

| Report | Covers | Method |
|---|---|---|
| `evidence/audit-A-recovery-shutdown-health.md` | 3.1/Issue 30, 3.5, 3.6, Issue 29, 3.9, Issues 36, 37 | Read source and Boot/Spring jars; nothing run |
| `evidence/audit-B-signal-replay-correctness.md` | 3.2, 3.3, 3.7, 3.8, 3.10 | 3.2, 3.3, 3.8 reproduced with failing tests (`evidence/repro-tests/`); 3.7, 3.10 traced |
| `evidence/audit-C-host-integration-followups.md` | 3.4, 3.11, Issues 38, 39, handover §4.2, §4.3, §7 | Traced; nothing run |
| `evidence/research-D-k6-load-tests.md` | k6 grounding | Read repo, CI and current k6 docs |

## 2. Goals, non-goals, anti-goals

**Goals**

1. Every verified finding is fixed with a test that fails on today's code, or
   is explicitly documented or dropped with a reason (§5).
2. A k6 load suite exists, runs locally with one command and in CI by manual
   dispatch, and produces before/after numbers for the fixes it can measure.
3. `docs/open-issues.md` is the single list of what is still open at the end.

**Non-goals**

- Maven Central blockers (handover §5).
- New features from handover §6.4 (list/search API, workflow timeout,
  cancellation, child workflows, continue-as-new, cron, payload codec,
  dashboard auth). *Coordinator's call — see D1.*
- Reducing per-parked-workflow steady load (3.9 / Issue 12). It is measured by
  the k6 suite this cycle and fixed, if at all, in a later one.

**Anti-goals** — the cycle has failed if any of these happen, even with green CI:

- A fix that makes a run fail or compensate where today it would stand down or
  be abandoned.
- A fix that breaks replay of history written by the current build (mixed
  fleets during a rolling deploy must keep working).
- A load suite that gates PRs on latency measured on shared CI runners.
- An implementer quietly editing an existing test to make a change pass. A
  test that encodes the old behaviour is changed only as a named plan step.

## 3. Findings that changed since the handover

- **3.8 is real and wide.** A live activity returns the raw object; replay
  returns the Jackson round trip. `OffsetDateTime` with a non-UTC offset, a
  set without a fixed iteration order (`Set.of`, `HashSet`, a `TreeSet` copied
  into a record; a `LinkedHashSet` is unaffected), and a subtype under a
  declared supertype all stand a run down as `nondeterministic_history` after any crash and
  recovery. The replayed value itself differs, so workflow logic can diverge
  even with `maestro.replay.verify-arguments=false`.
- **Suspected data corruption, not yet proven on Postgres.** The JDBC store's
  `readTree` parses decimals as doubles, so a `BigDecimal` with ~17+
  significant digits may replay with a different value. Simulated with a text
  round trip only. WP1 starts by proving or disproving it against Postgres.
- **3.2 has a second source.** The timer-poller leader leaks a permit per timer
  fired for a workflow owned elsewhere; leaked entries hold the signal payload.
  `AwaitTimerStrayPermitTest.fireSleepTimer_stillUnparksTheTimerKey` asserts
  the timer leak as intended.
- **3.3 reproduced.** After admin Retry the run stands down on every recovery
  pass, is `RUNNING` so Retry is refused, and only terminate exits.
- **3.5b is wider.** All five background loops die on an `Error`, including the
  instance-lock renewer, which nothing watches: locks then expire ~30 s after
  acquisition, peers adopt and fence repeatedly, health stays UP.
- **Issue 30 as filed is incomplete.** Gating adoption on capacity without
  re-polling when a slot frees turns a ~500 s drain of 5,000 runs into ~4 h.
  With `maestro.recovery.enabled=false` the startup pass must stay ungated.
- **Issue 29's suggested interrupt fix looks unsafe** (inferred): the restored
  interrupt flag would break the waiter's own lock release.
- **Issue 36 as filed cannot work:** `@MaestroTest` has no Spring context to
  bind `maestro.worker.*` from.
- **3.4's fix has a trap.** With Maestro's Kafka beans not default candidates,
  any unqualified `KafkaTemplate<String, byte[]>` injection silently gets
  Boot's template (inferred: `ClassCastException` on send). Affects Maestro's
  own wiring, all samples, and hosts who followed the docs.
- **3.11a is 300 batches, not 100** (100 per status × 3). **3.11b** also has no
  leader election and no service filter.
- **`outOfOrder(true)` is load-bearing** for every database holding the retired
  V200–V202 rows; no test catches an edit to a shipped migration.

## 4. Work packages

Each package is one branch, one PR, one plan file in this directory
(`NN-<name>.md`), written after this spec is approved. Sizes are the
researchers' estimates.

### Lane A — engine core (sequential; all touch `maestro-core` engine classes)

| WP | Contents | Size | Notes |
|---|---|---|---|
| **WP0 Executor builder** | Issue 37: builder; migrate the 2 production and ~104 test call sites; remove the ten constructors (D6). No behaviour change. | M | First, because every later package would otherwise add call sites to migrate. Mechanical; merges fast. |
| **WP1 Replay fidelity** | 3.8: the live path returns the same round-tripped value replay returns; a result that cannot be read back is handled per D9. Exact decimal parsing in the JDBC store for typed `BigDecimal` targets only (C1), preceded by a Postgres integration test that settles whether corruption occurs. Rule 12 in `docs/pitfalls.md` (and the rule count in `CLAUDE.md`). | S + S/M | Red tests: `AuditB38*`. Must stay replay-compatible with existing history. Merges only after WP9's seeded-history fixture is on `main`. |
| **WP2 Wake, timers, activity lock** | 3.2: store a wake permit only for a workflow running on this node (both signal and timer sources). 3.7: poll due timers on the database clock, WARN on skew. 3.10: remove the activity lock (D2). | S + S + S | Red test: `AuditB32*`. Changes `AwaitTimerStrayPermitTest` deliberately. 3.10 shares `ActivityInvocationHandler` with WP1, hence after it. |
| **WP3 Admin retry** | 3.3 with Issue 33: on retry delete a failure memo only if it is the failure that ended the run and nothing was recorded after it in its lane. The catch-alert-rethrow case is handled per D10. | M | Red test: `AuditB33*`. Touches `WorkflowStore` Javadoc contract and `AbstractJdbcWorkflowStore`; the in-memory store must match. |
| **WP4 Loop liveness and health** | 3.5b: one guard for all five background loops incl. the lock renewer — rethrow `MaestroControlFlowError`, log and back off on any other `Throwable`. 3.5a: a probe timeout reports UNKNOWN, not DOWN; document the indicator as readiness-only. | S + S | Also the two tests for the untested defensive branches (§5). |
| **WP5 Shutdown** | Issue 29: close the governor and pass a poison permit down the queue (no interrupts). 3.6: one shared deadline across all shutdown steps; asynchronous `stop(callback)`; fix the handler's Javadoc; document grace arithmetic. `maestro.shutdown.timeout` default per D11. First tests for `GracefulShutdownHandler`. | S + S | |
| **WP6 Recovery capacity** | Issue 30 / 3.1: reserve a slot without blocking before taking the lock; re-poll as soon as a slot frees; startup pass off the `ApplicationRunner` thread; ungated when the recovery poller is disabled. Recovered runs must not starve under sustained load (C2). Then Issue 36: an opt-in limits option on `TestWorkflowEnvironment` (C3). | M + S | Depends on WP4 (loop guard) and WP5 (governor). k6 scenario 2 is its before/after. |

### Lane B — starter, store, schema (parallel with Lane A)

Order: WP9 → WP7 → WP10 → WP8. WP9 goes first because its seeded-history
fixture, captured at `cbf5a2a`, is the only automated guard for the second
anti-goal and must be on `main` before WP1 or WP3 merge. WP8 goes last and
starts only after WP6 has merged: it shares the store SPI files with WP1–WP3,
the sweeper and janitor loops with WP4, and `StartupRecoveryRunner` with WP6.

| WP | Contents | Size | Notes |
|---|---|---|---|
| **WP7 Kafka coexistence** | 3.4: `defaultCandidate = false` on Maestro's three Kafka beans and a qualifier at every by-type injection site, with a context test built from real `AutoConfigurations.of(...)` proving the engine gets Maestro's template and a host gets Boot's. Delete the dead `AdminEventPublisher` bean. 3.11c (D3). Correct `docs/configuration.md` on the Kafka transaction manager after testing the claim. Close `tasks/todo.md:343`. | M | Breaking for hosts injecting `maestroKafkaTemplate` unqualified (D5): release note. |
| **WP8 Store hygiene** | 3.11a: pause between retention batches for as long as the last batch took. 3.11b: batched janitor delete via a default SPI overload, service-scoped. Issue 39: advisory startup query, one WARN per foreign `service_name`. | S ×3 | Starts after WP6 merges (see order above). |
| **WP9 Schema safety** | Rewrite the `outOfOrder` comment; test a history holding V200–V202. Frozen SQL fixture of the shipped migration set (from `git show`), then a seeded-history upgrade test. Production guidance for `schema.mode` and DDL privileges (3.11d). | S + S + M | |
| **WP10 Wake-listener shutdown** | Issue 38: join the poll thread, then `UNLISTEN`; no interrupt of a connected poll. | S | Reproduce the WARN first; it was traced, not seen. |

### Lane C — k6 load suite (parallel; new files only, plus meters)

| WP | Contents | Size |
|---|---|---|
| **WP11 Load-test foundation** | Top-level `perf/`; docker compose with Postgres, Kafka and N replicas of a new unpublished `maestro-samples/sample-perf` app running Maestro's true defaults; k6 pinned to 2.3.0; one local command; `workflow_dispatch` CI job, never on PRs. New meters the suite needs (workflow duration timer, slot-usage gauge) and the `maestro.run.abandoned{reason}` counter from handover §4.2 — all meter changes this cycle live in this package — documented in `docs/observability.md`. Scenario 1 (throughput ceiling at 20/20 with Boot's default pool) and scenario 3 (steady DB load at 1k–10k parked runs). Update `docs/test-plan.md`, which lists load testing as out of scope. | M–L |
| **WP12 Finding-linked scenarios** | Scenario 2 (5,000-run recovery drain, 1 vs 2 nodes), 4 (signal throughput across N nodes, permit growth), 5 (pool exhaustion vs health), 6 (retention sweeper impact). Each is run against the commit before its fix and after; both results are recorded in the plan's review section. | M |

Pass/fail in the suite rests on statement counts per workflow and on
comparisons within one run. Latency thresholds are wide and advisory. Kafka-
delivered signals are out of scope (the k6 Kafka extension needs a custom
build); REST signals already exercise cross-node wake.

Before/after rule (C4): Lane A does not wait for Lane C. Scenarios 2, 4, 5 and
6 each need a "before" for WP6, WP2, WP4 and WP8. When a fix has merged before
its scenario exists, the "before" is run on a throwaway branch: the fix's
merge base with the `perf/` and `sample-perf` commits cherry-picked on top.
The result records both commits. `sample-perf` sets
`maestro.admin.events.enabled` explicitly and prints it in every result, and
the scenario 1 and 3 baselines are re-run after WP7 merges, since D3 changes
the default they run at.

### Lane D — documents and housekeeping (one PR, any time)

**WP13:** `docs/open-issues.md` §1–§2 and PRD drift (Valkey, RabbitMQ, Postgres
transport); stale fencing note in `docs/test-plan.md`; pitfalls rule on
workflow operations inside an activity body (rule 13, after WP1's rule 12 has merged); the three cosmetic items from
handover §4.2; bump the 17 `actions/*@v4` pins after reading each changelog;
reconcile `tasks/todo.md`; file every finding in this spec that has no issue
number. Remote branches and worktrees from the last cycle are deleted only on
the maintainer's word.

## 5. Dropped, with reasons

- **SQLSTATE read from the first `SQLException`, not `getNextException`** —
  affects log text only; there is no batch code path.
- **Decision reads throwing out of stand-down catch arms** — the run is still
  safely abandoned; only the reason label differs.
- **Untested defensive branches** (`catch (Error)` latch, `executeParallel`
  store-fault pass) — kept, folded into WP4 as two tests rather than dropped.

## 6. How the cycle is run

As the last cycle (handover §8), which worked:

- One plan per work package, written by an Opus planner from this spec and the
  evidence report, reviewed by the coordinator before dispatch.
- One Opus implementer per task in a dedicated worktree; a fresh Opus reviewer
  per task; one whole-branch review; a single fix wave; then the PR.
- Test first. Every fix starts from a test that fails on the unfixed code and
  asserts a positive fact, and the reviewer re-runs it against the reverted fix.
- An implementer who hits a product decision, or a test that encodes the old
  behaviour and is not named in the plan, stops and asks.
- Implementers commit at every coherent checkpoint. `./gradlew --no-watch-fs`.
  No `git stash`. No two Gradle runs on one module in one tree.
- Lanes A, B and C run concurrently, each in its own worktree; within a lane
  PRs are sequential. At most three implementers are live at once.
- Gates per PR: Build & Test; nightly E2E; chaos PR-gate ×3. The 2 h soak runs
  on WP1, WP2, WP3, WP5 and WP6 before merge. The maintainer merges.

## 7. Decisions

Coordinator's defaults are stated; each stands unless the maintainer changes it.

| # | Decision | Default |
|---|---|---|
| D1 | Are the §6.4 feature gaps part of this cycle? | No — hardening only; features get their own specs. |
| D2 | Remove the activity lock and its documented lock key (3.10)? | Yes. |
| D3 | Flip `maestro.admin.events.enabled` to `false` by default (3.11c)? | Yes; nothing is released, and it saves two Kafka sends per activity and a startup dependency on a topic. |
| D4 | k6 suite: numbers or gate; dedicated perf app and new meters? | Numbers first; gate only on statement counts; yes to `sample-perf` and the meters. |
| D5 | Accept that 3.4 breaks hosts injecting `maestroKafkaTemplate` unqualified? | Yes, with a release note. |
| D6 | Remove the ten `WorkflowExecutor` constructors outright (Issue 37, WP0)? | Yes; nothing is released. |
| D7 | Live activity results become deserialized copies (3.8): object identity with what the activity returned is lost, at one extra deserialization per activity. | Yes; it is the only way live and replay agree. |
| D8 | Handover §6.2 (service filter final) and §6.3 (20/20 defaults). | Filter is final, Issue 39's WARN is the mitigation; 20/20 is revisited once scenario 1 has numbers. |

From the builder check — the maintainer accepted each recommendation (2026-11-12):

| # | Decision | Recommendation |
|---|---|---|
| D9 | WP1: a live activity result that cannot be read back by Jackson. Today the run completes and only stands down if it is ever replayed. Failing the live run would breach the first anti-goal. | WARN, count it on a meter, and return the raw value — today's behaviour for that case; never fail the run. |
| D10 | WP3: a workflow that catches an activity failure, runs an alert activity, then rethrows. Under WP3's rule the caught failure memo is kept, so Retry replays to the same failure and compensates again; today it ends in a stand-down. Alternative: on Retry, truncate everything recorded after the caught failure in its lane, so the primary and the alert run again live. | Truncate: it is what an operator pressing Retry means, and activities must be idempotent already. The planner confirms it is safe for parallel branches before building. |
| D11 | WP5: with an asynchronous `stop(callback)`, the 30 s `maestro.shutdown.timeout` default equals Boot's 30 s `timeout-per-shutdown-phase`, so a default setup could lose its `DataSource` mid-drain. | Lower the default to 20 s and document the arithmetic. |

Coordinator's calls from the builder check (settled unless overruled):

- **C1** Exact decimal parsing applies to typed `BigDecimal` targets only.
  `Object` and `Map<String, Object>` targets keep getting `Double`, so an
  in-flight workflow that casts to `Double` still replays after the upgrade.
- **C2** Recovery reserves a slot without blocking when one is free, and
  otherwise may queue a bounded number of adoptions (at most one queue's
  concurrency) in the same first-in-first-out line as fresh starts, so a
  recovered run takes its turn instead of waiting for an idle moment.
- **C3** The test kit's limits option is opt-in this cycle; defaulting user
  tests to 20/20 is filed as a follow-up.
- **C4** The before/after rule in Lane C above.

## 8. Left to the planners

Exact class and method shapes; the builder's API; the loop guard's back-off;
the wording of new config properties and meters; k6 script structure and the
compose topology; how the frozen migration fixture is stored. Any new public
type, config property, SPI method or migration is listed in the plan's first
section so the maintainer sees the added surface before it is built.
