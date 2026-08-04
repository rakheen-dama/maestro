# Task 2 report — the demo stack (compose + Prometheus + Grafana + Jaeger)

Branch `worktree-demo`. All six brief steps done and verified against the
running stack, not against config files. Evidence in `demo/.evidence/`, each
file carrying a pwd / HEAD / branch / timestamp identity header.

---

## 1. Prometheus targets UP — no `network_mode: host` needed

`host.docker.internal` resolves from inside the Prometheus container on this
machine, so the compose file keeps its bridge network. Proven by Prometheus's
own API, not by reading `prometheus.yml`:

`demo/.evidence/task-2-prometheus-targets-up.log` contains three
`"health": "up"` entries, for
`http://host.docker.internal:809{1,2,3}/actuator/prometheus`.

`maestro-admin` is deliberately not a target — the previous agent established
its jar has no `micrometer-registry-prometheus`, so `/actuator/prometheus`
cannot exist there whatever the exposure list says. That finding stands and
`prometheus.yml` documents it inline.

**Evidence-tracking fix:** the repo root `.gitignore` excludes `*.log`, so every
task-2 evidence file was sitting untracked (task-1's had been force-added).
`demo/.gitignore` now carries `!.evidence/*.log`, and the task-2 logs are in
git.

## 2. The dashboard — `demo/grafana/dashboards/maestro.json`

Five panels per the brief. **Every metric name was taken from a live scrape
before the JSON was written**, archived as
`demo/.evidence/task-2-metric-names-scrape.log`. The Prometheus names differ
from the Micrometer names in the brief, which is exactly why the brief said not
to trust the list:

| Panel | Query source |
|---|---|
| Workflows started / completed / failed | `maestro_workflow_{started,completed,failed}_total` |
| Workflows parked (gauge) | `maestro_workflows_parked` |
| Activity duration p50/p95 by activity | `histogram_quantile` over `maestro_activity_duration_seconds_bucket` |
| Recovery adoptions | `maestro_recovery_adopted_total` |
| Stand-downs by reason | `maestro_standdown_total` by `reason` |

**Rendering was verified through Grafana**, not through Prometheus — every
panel query was executed via `POST /api/ds/query` against datasource uid
`maestro-prometheus`, which also proves the provisioning and the uid pinning.
`demo/.evidence/task-2-grafana-dashboard-renders.log`: 8 of 9 target queries
return data (174, 174, 107, 171, 186, 186, 171, 181 points).

Two things worth knowing:

- The p50/p95 panel only works because `start-services.sh` asks Micrometer for
  a percentile histogram on `maestro.activity.duration` (a plain Timer
  publishes only `_count`/`_sum`/`_max`, which cannot answer "p95"). That was
  already in the script; this task confirmed 414 `_bucket` series exist.
- **The stand-down panel is meant to stay at zero.** Its original description
  claimed stand-downs were "a node declining to run a workflow it does not hold
  the lock for". `StandDownReason` has no such value — it is exactly
  `UNKNOWN_EVENT_TYPE`, `UNKNOWN_EVENT_PAYLOAD` and `STALE_RUN`, all
  pathological. I tried to raise it and could not: four loans round-robined
  across two nodes, plus a loan created on node A and signalled entirely
  through node B, all left the counter unregistered. The description now says
  so, and the panel carries a `vector(0)` companion series so a healthy stack
  renders a flat zero instead of "No data".

## 3. The Jaeger cross-service trace — and the bug that was hiding it

**`TRACE_ID = d055f5961a6028a5d24d329ff11bc896` — 31 spans, 3 services**
(`sample-loan-application-service` 27, `sample-verification-gateway-service` 3,
`sample-underwriting-service` 1). Full span list in
`demo/.evidence/task-2-jaeger-cross-service-trace.log`; UI at
`http://localhost:16686/trace/d055f5961a6028a5d24d329ff11bc896`.

This did not work at first, and the failure mode was the dangerous kind: Jaeger
listed all three services and had plenty of traces, so it looked healthy. But
every trace was single-service — each service was opening a fresh root trace.

**Root cause.** The three services talk to each other over the *sample's own*
domain topics (`loans.verification.*`, `loans.underwriting.*`), published with
an injected `KafkaTemplate<String, byte[]>`. Maestro's `KafkaTracePropagation`
injects W3C `traceparent` only on the `maestro.tasks.*` / `maestro.signals.*`
topics the engine owns, so it never saw those records. Consuming the topic
showed `NO_HEADERS` on every record.

The obvious lever — `spring.kafka.template.observation-enabled=true` — is
**inert here**, and silently so. It configures Boot's auto-configured
`kafkaTemplate` bean, and Boot backs that bean off entirely
(`@ConditionalOnMissingBean(KafkaTemplate.class)`, verified in
`spring-boot-kafka-4.0.5` bytecode) because `KafkaMessagingAutoConfiguration`
already contributes `maestroKafkaTemplate`. The only `KafkaTemplate` in the
context is Maestro's. Setting the property changed nothing on the wire — I
tried it and re-read the topic to confirm.

**Fix**, via the extension point the engine deliberately provides: its template
is `@ConditionalOnMissingBean(name = "maestroKafkaTemplate")`, so each of the
three sample services now defines a bean of that name with
`setObservationEnabled(true)` (`config/ObservedKafkaTemplateConfig.java`).
Engine and domain traffic share one observed template, so no injection point
needs qualifying and no producer class changed. The consumer side is enabled
from the demo launcher with `-Dspring.kafka.listener.observation-enabled=true`.

After the fix, records on `loans.underwriting.requests` carry
`traceparent:00-d055f5961a6028a5d24d329ff11bc896-95230c42b06ffe29-01`.

**Scope note, flagged deliberately.** This is the one change outside `demo/`.
It touches three sample services' `src/main`, which the brief's constraints
leave open (only `sample-loan-origination/e2e/` and the chaos harness are
off-limits); `maestro-core` is untouched. I judged it necessary rather than
optional: the brief calls this the demo's most important artifact, and it was
unobtainable by demo-side configuration alone. Verified afterwards that the
three services' Gradle test tasks pass and that 10+ loans reach
`COMPLETED`/`FUNDED` through the real stack, including the cross-node case.

**Known limit, not papered over:** the downstream services contribute Kafka
`process` spans to the trace, but their own `maestro.workflow.run` spans are
not attached to it — the workflow runs on a separate virtual thread whose span
context comes from the engine's start path, not the listener's observation
scope. The trace does span all three services, which is what the demo needs,
but it is a per-service *entry point* view downstream, not a full workflow view.
Closing that would be an engine change.

## 4. Peak memory — 2.27 GiB against a ~4 GB budget

`demo/.evidence/task-2-peak-memory.log`. Sampled every 3s for 200s while six
loans ran end to end; per-component peak, then summed (pessimistic — they do
not all peak together).

| Component | Peak MiB |
|---|---|
| kafka | 656 |
| verification-gateway-service (JVM) | 361 |
| underwriting-service (JVM) | 357 |
| loan-application-service (JVM) | 356 |
| maestro-admin (JVM) | 265 |
| grafana | 131 |
| postgres | 111 |
| prometheus | 56 |
| jaeger | 23 |
| valkey | 13 |
| **total** | **2329 MiB = 2.27 GiB** |

Roughly 1.7 GB of headroom. Kafka is the largest consumer at 64% of its 1g
`mem_limit`; nothing else is near its limit. `TWO_NODE=1` adds a fifth JVM
(~360 MiB) for ~2.7 GiB, still inside budget. JVM figures are RSS, not heap —
the whole process, against `-Xmx256m`.

## 5. `TWO_NODE=1` verified

`demo/.evidence/task-2-two-node-and-standdown.log`: four Prometheus targets UP
with node B (8094) discovered through `file_sd` — so the file-SD wiring works
and a single-node demo really does show zero DOWN targets. Work landed on both
nodes, and a loan created on node A with every signal sent to node B reached
`COMPLETED`/`FUNDED`.

The stack was returned to the default single-node shape afterwards: three
targets, all UP, `demo/targets/loan-application-b.json` removed.

## Stack state at hand-off

Containers up; four host JVMs running with pid files in `demo/.run/`. Stop with
`demo/scripts/stop-services.sh`, then
`docker compose -f demo/docker-compose.yml down -v`.

## For Task 4's runbook

1. `docker compose -f demo/docker-compose.yml up -d`, then
   `demo/scripts/start-services.sh` (add `DEMO_SKIP_BUILD=1` to reuse jars).
2. Grafana 3000 lands directly on the dashboard; Prometheus 9090; Jaeger 16686;
   admin 8080; loan services 8091-8093.
3. Expect the stand-down panel to read zero — that is the healthy state, and
   the runbook should say so rather than let someone read it as broken.
4. The whole stack peaks around 2.3 GiB.

---

# Fix round 1

## F1 — clean-machine Kafka topic race (`demo/scripts/start-services.sh`)

Real, and the reviewer's reading of the window was exact. Preflight TCP-probed
the broker, which binds 29093 well before `kafka-init` creates the topics, and
`docker compose up -d` **returns inside that window** — so the documented
first-run sequence could start the JVMs against a topic-less broker and fail
with `UNKNOWN_TOPIC_OR_PARTITION`.

Preflight now gates on the topics themselves, asked of the broker via the kafka
container's own CLI. The required list is **derived** by grepping `--topic` out
of `docker-compose.yml` rather than duplicated in the script, so a topic added
to compose can never silently go ungated (parses to 11).

Caught in the act, from `docker compose down -v` with the two halves run
back to back as a user would
(`demo/.evidence/task-2-kafka-topic-gate.log`):

```
10:55:51 [demo] Waiting for 11 Kafka topics to exist...
10:56:02 [demo] All Kafka topics present
```

Eleven seconds of genuine waiting. `kafka-init`'s own log timestamps
`All Kafka topics created successfully.` at `2026-08-04T08:56:02.265Z` — the
gate cleared exactly when the topics appeared, which is what distinguishes a
real wait from a `sleep`. Cold stack then verified: three targets UP, one loan
`COMPLETED`/`FUNDED`.

## F2 — filed as Issue 23; library untouched

Filed in `docs/open-issues.md` as **Issue 23 — Library defect, Critical, Open**,
indexed in §4 and written up in §5 with both citations
(`KafkaMessagingAutoConfiguration.java:105-110`,
`MaestroSignalListenerBeanPostProcessor.java:213-219`), the affected property
surface, the recommended fix, the user-side workaround and a Done-when. No
library code changed; the sample-level stopgap stays exactly as it was.

I verified the ruling's two mechanisms rather than restating them, archived in
`demo/.evidence/task-2-kafka-template-library-defect.log`:

- Boot's `kafkaTemplate` is `@ConditionalOnMissingBean(KafkaTemplate.class)`
  and its `kafkaProducerFactory` is
  `@ConditionalOnMissingBean(ProducerFactory.class)` — read out of
  `spring-boot-kafka-4.0.5` bytecode. Maestro's beans are conditional on bean
  *name*, so they shadow Boot's by *type*. That asymmetry is the whole bug.
- `grep -c runWithExtractedContext` over the bean-post-processor returns **0**.
- The consequence is now measured, not inferred. On the running demo — where
  the producer side demonstrably *is* injecting `traceparent` — every signal row
  lands with `trace_context` NULL, including `underwriting.decision` (1) and
  `verification.result` (3), which arrive on domain topics carrying the header.
  (`document.uploaded`/`package.signed` are NULL too but are *not* evidence:
  they enter over REST and have no header to extract. Noted in the issue so
  nobody over-reads the table.)

`docs/observability.md`'s "one connected trace" promise now carries an explicit
scope limit: it holds for `maestro.tasks.*`/`maestro.signals.*` only, states
plainly that `spring.kafka.template.observation-enabled` is inert, gives the
`maestroKafkaTemplate` override users need today, and cross-references Issue 23
and `cross-service.md`.

## F3 — evidence discipline

Panel point counts in §2 corrected to the archived values: **174, 174, 107,
171, 186, 186, 171, 181**. All eight grep back out of
`task-2-grafana-dashboard-renders.log`.

The admin-jar claim was worse than the reviewer could see from the log alone.
The enumeration was unevidenced *and so was the load-bearing
`grep -ci prometheus` → 0*: that capture ran with `cwd=<repo>/demo`, where the
relative glob `maestro-admin/build/libs/maestro-admin-*.jar` matched nothing,
so `unzip` never ran. The empty grep and the zero were artefacts of a path that
did not resolve, not facts about the jar. Only the 404 was ever real.

Re-run from the repo root against a jar shown to exist
(`task-2-admin-no-prometheus-endpoint-rerun.log`). **The conclusion survives and
is now actually proven:** `grep -ci prometheus` → 0, micrometer entries exactly
`jakarta9` / `core` / `observation` / `commons` (no registry), endpoint 404.
`demo/prometheus.yml` now cites the rerun and warns readers off the original
file's jar half.

## F4 — startup robustness

`wait_for_http` accepted any 1xx–4xx, so a 404 from an unexposed actuator read
as healthy. Narrowed to 2xx — which also makes it *more correct*, not merely
stricter: Spring Boot's health endpoint answers 503 while components are still
starting, so 2xx keeps it waiting through startup instead of declaring victory
on the first response of any kind.

`assert_port_free` ran inside `start_jvm`, so a conflict on the third port
aborted under `set -e` with the first two JVMs already running and pid files
written. All ports are now checked in preflight before anything starts, and an
`EXIT` trap stops any JVM *this run* started if a later step fails. Negative
test in the same evidence file: a second invocation reports all four ports at
once, starts nothing, leaves the running pids byte-identical, and exits 1.

## Deferred, as agreed

The 8092 `lastScrape` skew (cosmetic) and the `vector(0)` caveat (for Task 4's
runbook to note) are untouched.
