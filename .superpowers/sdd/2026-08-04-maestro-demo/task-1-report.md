# Task 1 Report — Observability wiring for the three loan services

**Identity**
- pwd: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/demo`
- git HEAD (at commit time): `67f909e` (parent `69095d3`)
- branch: `worktree-demo`
- timestampUtc: 2026-08-04T07:16Z – 09:2x (verification session)

Evidence artifacts (each embeds its own pwd/HEAD/branch/timestamp header),
committed under `demo/.evidence/`:
- `task-1-prometheus-loan-application.log`
- `task-1-prometheus-verification-gateway.log`
- `task-1-prometheus-underwriting.log`
- `task-1-otlp-property-fix-proof.log`
- `task-1-otlp-graceful-degradation.log`
- `task-1-full-build.log`
- `task-1-e2e.log`

---

## 1. What was done

1. `gradle/libs.versions.toml` — added `micrometer-registry-prometheus` and
   `opentelemetry-exporter-otlp` catalog entries (both BOM-managed, no
   inline versions, matching the existing `micrometer-core` style).
2. Each of the three services' `build.gradle.kts`
   (`loan-application-service`, `underwriting-service`,
   `verification-gateway-service`) — added:
   - `implementation(libs.spring.boot.starter.actuator)`
   - `implementation(libs.spring.boot.micrometer.tracing.opentelemetry)`
   - `runtimeOnly(libs.micrometer.registry.prometheus)`
   - `runtimeOnly(libs.opentelemetry.exporter.otlp)`
   - `runtimeOnly(libs.micrometer.tracing.bridge.otel)` — **not in the
     brief's literal list; added after runtime verification showed it was
     required** (see §3).
3. Each service's `application.yml` — exposed
   `health,info,prometheus,metrics`, set `management.tracing.sampling.probability: 1.0`,
   the OTLP tracing endpoint (see §3 for the corrected property name), and
   `maestro.observability.{metrics,tracing}.enabled: true`.
4. Drove real loans through all three services (both a manually-run
   `bootRun` trio and, separately, the existing `e2e/run-e2e.sh`) against
   the existing `maestro-samples/sample-loan-origination/docker-compose.yml`
   infra (Postgres 5433 / Valkey 6380 / Kafka 29093) and captured
   `/actuator/prometheus`, `/actuator/beans`, and `/actuator/conditions`
   evidence.
5. Ran a full `./gradlew build` and the existing loan-origination E2E
   harness (`e2e/run-e2e.sh`, all 10 scenarios) unchanged, per the brief's
   explicit "do not modify" constraint on that directory.
6. Committed the wiring + evidence as one checkpoint; this report is a
   follow-up (`docs` only).

`maestro-core` was never touched. `maestro-samples/sample-loan-origination/e2e/`
and the chaos harness were never touched (confirmed by `git status`/`git diff`
showing zero changes there). No Kafka topic was auto-created — the sample's
existing `kafka-init` pre-creates every topic used, unchanged.

## 2. Meter proof — verbatim `grep -c '^maestro_'`, quoted from the archived logs

From `demo/.evidence/task-1-prometheus-loan-application.log`:
```
$ curl -s localhost:8091/actuator/prometheus | grep -c '^maestro_'
12
```

From `demo/.evidence/task-1-prometheus-verification-gateway.log`:
```
$ curl -s localhost:8092/actuator/prometheus | grep -c '^maestro_'
13
```

From `demo/.evidence/task-1-prometheus-underwriting.log`:
```
$ curl -s localhost:8093/actuator/prometheus | grep -c '^maestro_'
4
```

All three non-zero. The loan-application and verification-gateway logs
include real counters/timers driven by an actual loan
(`maestro_workflow_started_total`, `maestro_activity_duration_seconds_*`,
`maestro_signal_consumed_total`, `maestro_timer_fired_total`,
`maestro_workflow_completed_total`) — not just the two always-registered
gauges (`maestro_workflows_running`, `maestro_workflows_parked`) and the two
recovery counters, which is what the underwriting-service log shows (no
loan reached the human-underwriting desk in that particular drive, so it
only shows the four meters that are populated regardless of workflow
traffic). This matches `docs/observability.md`'s meter catalog exactly —
14 possible meter *names*, a subset populated depending on what each
service actually did.

This directly proves `MaestroObservabilityAutoConfiguration` fired for
real (per the task context's warning that it previously shipped inert):
the meters are runtime-emitted values from live activity executions, not
config-echo.

## 3. Surprise: the brief's OTLP property name is wrong for Spring Boot 4.0.5 — corrected

The brief specifies (verbatim):
```yaml
management:
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

This is the **Spring Boot 3.x** property name. Spring Boot 4.0.5 moved OTLP
tracing config into the modular `spring-boot-opentelemetry-autoconfigure`
package's own prefix: **`management.opentelemetry.tracing.export.otlp.endpoint`**.

**How this was found and proven, not assumed:**

1. Wired the brief's literal property, added the dependencies the brief
   listed, started `loan-application-service`, drove a loan through it.
   `/actuator/prometheus` showed meters fine, but the log had **zero**
   tracing-related output of any kind — not even a connection-refused from
   the OTLP exporter, which was expected (Jaeger deliberately not running).
2. Confirmed via `lsof -p <pid>` that the JVM made **no network attempt**
   to `:4317` or `:4318` at all (not even a failed/closed one) — ruling out
   "it tried and failed silently."
3. Confirmed via a temporarily-exposed `/actuator/beans` (debug run only,
   `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=*`, never committed) that
   `Tracer`/`Propagator` beans **did** exist and Maestro's
   `TracingEngineObserver` bean **was** registered — so the engine side
   was correctly wired; something below it wasn't.
4. Also confirmed (before finding the property bug) that spans were
   genuinely being created: real, distinct W3C trace/span-ID pairs showed
   up in the log's MDC-correlated bracket for different requests, and the
   same trace ID repeated across multiple spans within one request
   (segment + activity span) — ruling out "it's a no-op tracer."
5. `/actuator/beans` showed no `SpanExporter` implementation bean at all
   (only the `spanExporters` composite factory and connection-details/
   properties beans) — the OTLP exporter itself was never constructed.
6. `/actuator/conditions` gave the definitive answer, quoted verbatim from
   that debug session:
   ```
   OtlpTracingConfigurations.ConnectionDetails#otlpTracingConnectionDetails
    - OnPropertyCondition @ConditionalOnProperty (management.opentelemetry.tracing.export.otlp.endpoint)
      did not find property (management.opentelemetry.tracing.export.otlp.endpoint)
   ```
   i.e. Boot 4.0.5 gates the OTLP `SpanExporter` bean on the **new**
   property name; the brief's old name simply isn't read by anything,
   so the exporter is permanently absent — this is the exact "shipped
   inert" failure class the task context warned about, just one layer
   down (Boot's own auto-config this time, not Maestro's).
7. Corrected all three `application.yml` files to
   `management.opentelemetry.tracing.export.otlp.endpoint`. Restarted,
   confirmed via `/actuator/beans` that a genuine `otlpHttpSpanExporter`
   bean (`io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter`)
   now exists.
8. **Positive proof it actually exports**: stood up a throwaway local HTTP
   listener on `127.0.0.1`/`[::1]:4318` (standing in for a collector, since
   Jaeger is deliberately out of scope for this task), drove a loan, and
   captured real OTLP/HTTP protobuf POSTs to `/v1/traces` — quoted verbatim
   in `demo/.evidence/task-1-otlp-property-fix-proof.log`:
   ```
   RECEIVED POST /v1/traces len=1933 from=('::1', 56416, 0, 0)
   RECEIVED POST /v1/traces len=561 from=('::1', 56417, 0, 0)
   RECEIVED POST /v1/traces len=561 from=('::1', 56424, 0, 0)
   RECEIVED POST /v1/traces len=561 from=('::1', 56426, 0, 0)
   ```
9. **Negative proof it degrades gracefully with the real (Jaeger-less)
   verification setup**: with no listener on `:4318` (the actual state
   for this task's verification, per the task context), the app stays
   healthy (`/actuator/health` → `200`) while the exporter logs its
   failure — quoted verbatim in
   `demo/.evidence/task-1-otlp-graceful-degradation.log`:
   ```
   ERROR ... i.o.exporter.internal.http.HttpExporter : Failed to export spans.
   The request could not be executed. Full error message: Failed to connect
   to localhost/[0:0:0:0:0:0:0:1]:4318
   ```

**Task 2's implementer needs to know:** configure Jaeger's OTLP receiver
endpoint via `management.opentelemetry.tracing.export.otlp.endpoint` (or
rely on the `${OTEL_EXPORTER_OTLP_ENDPOINT:...}` env-var default already
wired into all three `application.yml` files), **not**
`management.otlp.tracing.endpoint` — the latter silently does nothing on
Spring Boot 4.0.5.

## 4. Second surprise: `micrometer-tracing-bridge-otel` had to be added

The brief's dependency list (`spring-boot-starter-actuator`,
`micrometer-registry-prometheus`, `spring-boot-micrometer-tracing-opentelemetry`,
`opentelemetry-exporter-otlp`) is not sufficient on its own:
`spring-boot-micrometer-tracing-opentelemetry` does not transitively pull in
`io.micrometer:micrometer-tracing-bridge-otel`, and without that bridge on
the classpath, Boot's `OpenTelemetryTracingAutoConfiguration` (which is
`@ConditionalOnClass`-gated on the bridge's classes) never activates at
all — no `Tracer`, no `Propagator` bean, so Maestro's
`TracingEngineObserver` would never register even with the OTLP property
fixed. Confirmed by resolving the runtime classpath
(`./gradlew :...:dependencies --configuration runtimeClasspath`) before
and after: no `bridge-otel` line in the tree until added explicitly.
`gradle/libs.versions.toml` already declared this catalog entry (used only
by the starter's own tests); it just wasn't referenced by any sample
service. Added `runtimeOnly(libs.micrometer.tracing.bridge.otel)` to all
three services (see build.gradle.kts diffs — commented in place with this
reasoning).

## 5. Full build result

From `demo/.evidence/task-1-full-build.log` (`./gradlew build`, all modules
including Testcontainers-backed store/messaging/lock suites):
```
BUILD SUCCESSFUL in 7s
134 actionable tasks: 65 executed, 36 from cache, 33 up-to-date
```
No new warnings attributable to this change (the only warnings in the log
are pre-existing Testcontainers `PostgreSQLContainer`/`KafkaContainer`
deprecation notices in `maestro-admin`'s test support, unrelated to this
task).

## 6. Existing loan E2E result

Ran the existing `maestro-samples/sample-loan-origination/e2e/run-e2e.sh`
**unmodified**, full run (all 10 scenarios, default settings — Valkey lock
backend, single node except where a scenario itself starts a second node),
against the observability-wired services. Full transcript in
`demo/.evidence/task-1-e2e.log`, prefixed with the standard
`evidence-identity.sh` header.

Quoted verbatim from `demo/.evidence/task-1-e2e.log`'s `=== RESULTS ===`
section:
```
=== RESULTS ===
PASS 1. Happy path (co-borrower signs first)       14s
PASS 2. Out-of-order doc (orphan adoption)         15s
PASS 3. Conditions loop -> round-2 approval        15s
PASS 4. Withdrawal after rate lock (saga)          14s
PASS 5. Crash recovery (kill -9 + replay)          81s
PASS 6. Two-node loan-application (multi-node)     24s
PASS 7. Owner-kill -> peer adoption (multi-node)   77s
PASS 8. Rolling restart (graceful SIGTERM mid-flight) 42s
PASS 9. Timer-poller leader failover (verification-gateway) 106s
PASS 10. Cross-node admin retry/terminate          122s
09:26:15 [e2e] All scenarios passed.
```
All 10 scenarios PASS, 0 FAIL. **Nothing regressed.**

One incidental thing the harness's own log-scan surfaced (not a failure —
`run-e2e.sh` only fails a scenario on an explicit assertion, and none of
these did): with an OTLP-shaped listener that isn't there deliberately
(no Jaeger in this task's scope), every service's log accumulates
`i.o.exporter.internal.http.HttpExporter : Failed to export spans ... Failed
to connect to localhost/[0:0:0:0:0:0:0:1]:4318` / `Connection refused`
lines — the harness's own "WARN ... log contains ERROR/stack-trace lines"
check picked these up and printed them for visibility, exactly the expected,
harmless degradation described in §3/§9 below. Every scenario still passed;
the app never treats an OTLP export failure as anything but a background
warning.

## 7. Self-review against the brief

- [x] **Step 1** — both catalog entries added, BOM-managed, matching
  existing style.
- [x] **Step 2** — all four listed dependencies added to all three
  services, **plus** `micrometer-tracing-bridge-otel` (see §4; required,
  not optional — without it nothing in Step 3 has any effect).
- [x] **Step 3** — `application.yml` configured as specified, **except**
  the OTLP endpoint property, which uses the Boot-4-correct name (see §3);
  the YAML *shape* (nested endpoint under a `tracing.export.otlp` path) is
  functionally what the brief asked for, just at the corrected prefix.
- [x] **Step 4** — verified locally against the existing loan compose;
  `grep -c '^maestro_'` non-zero on all three services; archived.
- [x] **Step 5** — `./gradlew build` green; existing `e2e/run-e2e.sh` run
  unchanged, all 10 scenarios PASS (see §6).
- [x] **Step 6** — committed (`67f909e`,
  "feat(demo): actuator, Prometheus and OTLP wiring for the loan services").

**Deviations from the brief's literal text, both runtime-proven necessary,
not stylistic:**
1. OTLP endpoint property name corrected (§3).
2. `micrometer-tracing-bridge-otel` added (§4).

No other files were touched. `maestro-core`, `e2e/`, and the chaos harness
are untouched — confirmed via `git diff --stat` against the base commit.

## 8. Commits

```
67f909e feat(demo): actuator, Prometheus and OTLP wiring for the loan services
```
On `worktree-demo`, based on `69095d3` (the demo-plan commit, itself on top
of `945ccb4`). No `--no-verify`/`--no-gpg-sign` used.
