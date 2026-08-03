# Maestro Demo — Design Spec

Date: 2026-08-03. Status: approved in brainstorming; ready for an implementation plan.

## Purpose

A repeatable, laptop-runnable demo of Maestro with three deliverables: a live
demo environment, an operator runbook, and an HTML presentation with speaker
notes. Audience is **both** engineers evaluating adoption and
decision-makers — served by a ~10-minute impressive top followed by modular
deep-dive sections the presenter can drop into on demand.

## Grounding: what the loan sample already covers

Verified against the tree at `15b27dc` + green-main fixes.

**Covered by `sample-loan-origination` today:** durable activities and
memoization; `awaitSignal` (6 uses), `collectSignals` (3), `sleep` (3);
`randomUUID`/`currentTime`; saga compensation (withdrawal after rate-lock);
cross-service choreography across three services over Kafka; and ten E2E
scenarios covering crash recovery, orphan signal adoption, owner-death
adoption, rolling restart, timer-leader failover and cross-node admin
retry/terminate, on both Valkey and Postgres lock backends.

**NOT covered anywhere in the samples tree** (zero usages found):
`parallel()`, `retryUntil()`, `workflow.version()`, unknown-event stand-down,
and the observability added in the release-hardening cycle (no
Prometheus/Grafana/Jaeger wiring exists in the repo).

**Consequence for this demo:** the loan sample is a strong base but does not
cover Maestro's full surface. This spec closes the two gaps that carry
narrative weight (`parallel()` and `version()`), builds the missing visual
layer, and deliberately leaves `retryUntil()` and stand-down as slide
statements rather than staged scenarios — stand-down needs two engine builds
side by side, which costs more stage time than it earns.

## Deliverables

1. `demo/docker-compose.yml` — the full stack, memory-capped.
2. `demo/prometheus.yml`, `demo/grafana/` — scrape config, provisioned
   datasource, and one hand-authored Maestro dashboard (no configuration
   clicking on stage).
3. A `v2` source set in `loan-application-service` overriding only
   `LoanApplicationWorkflow`, producing `loan-application-v2.jar`. Both jars
   build in CI so v2 cannot rot.
4. `demo/RUNBOOK.md` — the operator script.
5. `demo/presentation/index.html` — self-contained deck with presenter notes.

## The v1 → v2 change (one story, two features)

`version()` guards the `parallel()` rollout:

- **v1** (today's code): verification runs sequentially.
- **v2**: verification fans out with `parallel()` — income, identity and
  property valuation concurrently — guarded by
  `workflow.version("parallel-verification", 1, 2)`.

On stage: loans are in flight, v1 is stopped, v2 is started. In-flight loans
keep the sequential branch because their `VERSION_MARKER` records version 1;
loans started after the deploy fan out. Both complete correctly. The proof is
two Jaeger traces side by side — a straight chain and a three-way fan-out —
from the same service at the same time.

Both jars are pre-built during setup so the stage action is `stop v1 → start
v2`, with the code diff shown on a slide rather than compiled in front of an
audience.

## Resource budget

Ten containers, ≈3.5–4 GB:

| Container | Notes |
|---|---|
| Postgres ×1 | two databases (`maestro`, `admin`) — one container instead of two |
| Kafka ×1, Valkey ×1 | as the existing sample |
| loan-application, underwriting, verification | `-Xmx256m` each |
| admin dashboard | existing `maestro-admin` module |
| Prometheus, Grafana, Jaeger (all-in-one) | the new visual layer |

Memory limits are pinned in compose rather than letting JVMs size themselves
from host RAM. A documented `TWO_NODE=1` toggle adds a second
loan-application instance for the adoption scenario; **off by default** so the
presenter can decide on the day.

## The 10-minute top

1. Start a loan; follow it across three services in the admin dashboard (real
   event log, sequence numbers, memoized results).
2. `kill -9` the loan-application process mid-workflow; restart it. The
   workflow resumes at the exact step; completed activities do not re-run.
3. Show the Jaeger trace: one connected trace across three services, spanning
   the durable park where the workflow slept awaiting a signal.
4. Withdraw a loan after rate-lock → saga compensation unwinds LIFO, visible
   in the dashboard.
5. Grafana: parked count, activity durations, recovery adoptions, live.

## Deep-dive sections (modular, non-linear)

1. **Rolling deploy** — the v1→v2 moment above. Strongest section.
2. **How memoization works** — walk `maestro_workflow_event` sequence numbers
   for one workflow, then crash-and-replay while pointing at which rows replay
   instantly versus execute live.
3. **The evidence** — chaos harness: 2-hour soak, 2,376 workflows, zero
   invariant violations, zero duplicate side effects; the four engine defects
   it found.
4. **Architecture** — one diagram: no central server, three SPIs, Postgres is
   truth and Valkey is optimisation.
5. **Determinism** — the constraint, `workflow.currentTime()`/`randomUUID()`,
   and `DeterminismChecker` catching a non-deterministic workflow live.
6. **Multi-node adoption** — `TWO_NODE=1` only; kill the owner, watch another
   instance adopt after lock TTL.

## Runbook structure

- **T-30 pre-flight:** pull images, build both jars, **pre-create Kafka
  topics** (a prior cycle lost 60s to an uncreated topic blocking
  `startWorkflow`), verify ports free, warm caches, run one throwaway loan
  end to end.
- **Process-identity check:** verify PID and build fingerprint before trusting
  anything on screen — a prior cycle was fooled by stale JVMs from an earlier
  run serving the probes.
- **Reset procedure:** one command back to a clean slate between rehearsals.
- **Per scenario:** exact command, what to point at, what should appear,
  expected timing, and **a stated fallback line** if it does not.
- **Teardown.**

## Presentation

Self-contained `index.html`: no CDN, no network at runtime, keyboard-driven,
with a presenter view showing notes and the next slide. Each slide carries a
`SAY:` block and a `DO:` block keyed to the runbook's scenario numbers so deck
and runbook stay in lockstep. Deep-dive sections are directly jumpable rather
than a fixed linear path.

## Explicitly out of scope

- Maven Central publishing (separate effort, after product review).
- `retryUntil()` and unknown-event stand-down as staged scenarios — slide
  statements only.
- Any change to the existing E2E scripts or the chaos harness.
- A general-purpose "feature museum" sample.
