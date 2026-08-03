# Maestro Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A laptop-runnable Maestro demo: one `docker compose up`, a Grafana/Jaeger/admin visual layer, a `version()`-guarded `parallel()` rollout you can perform live, an operator runbook, and a self-contained HTML deck with speaker notes.

**Architecture:** A new top-level `demo/` directory owns everything (compose, observability config, runbook, deck) so the existing samples and their E2E scripts are untouched. The three loan services gain actuator + Prometheus + OTLP wiring via configuration and a small dependency addition — no engine changes. A `v2` Gradle source set in `loan-application-service` produces a second jar whose only difference is the workflow class.

**Process model — deliberate:** infrastructure and the observability stack run in containers; the three loan services and `maestro-admin` run as **host JVMs from built jars**, exactly as the existing loan sample already does (`bootRun`), because neither the services nor the admin module has a Dockerfile today. This is also the better demo: `kill -9 <pid>` on a visible terminal process is more visceral and more obviously honest than `docker kill`, and swapping v1 for v2 is just starting a different jar. It cuts the container count from ten to seven, which matters on a laptop. Prometheus reaches the host services via `host.docker.internal`.

**Tech Stack:** Docker Compose, Prometheus, Grafana, Jaeger all-in-one, Spring Boot 4 actuator, Micrometer Prometheus registry, OTLP exporter via `micrometer-tracing-bridge-otel`, Java 25, Gradle Kotlin DSL.

**Binding spec:** `docs/superpowers/specs/2026-08-03-maestro-demo-design.md`. Domain reference: `demo/DOMAIN-BRIEF.md`. Both are on `main`.

## Global Constraints

- Java 25+ toolchain; Spring Boot 4; Jakarta EE 11 only; Jackson 3 (`tools.jackson`) never `com.fasterxml.jackson`; no Lombok.
- **`maestro-core` must not change.** This is a demo; if the demo cannot be built without an engine change, STOP and report rather than changing the engine.
- **Do not modify** `maestro-samples/sample-loan-origination/e2e/` or the chaos harness. The existing E2E scripts must keep passing untouched.
- Never auto-create Kafka topics — declare and pre-create them, as the existing sample does via `kafka-init`.
- Demo containers must have explicit memory limits; JVMs get `-Xmx256m`. Total budget ≈4 GB — seven containers plus four host JVMs.
- Every new dependency goes through `gradle/libs.versions.toml` (no inline versions).
- Evidence: each task archives verification output under `demo/.evidence/` with an identity header (pwd, `git rev-parse HEAD`, branch, timestamp). Quote only strings greppable from those files.
- Commit incrementally — every coherent green checkpoint.

---

## Task 1: Observability wiring for the three loan services

**Files:**
- Modify: `gradle/libs.versions.toml` (add `micrometer-registry-prometheus`, `opentelemetry-exporter-otlp`)
- Modify: `maestro-samples/sample-loan-origination/{loan-application-service,underwriting-service,verification-gateway-service}/build.gradle.kts`
- Modify: the three services' `src/main/resources/application.yml`
- Test: `demo/.evidence/task-1-*.log`

**Interfaces:**
- Produces: each service exposes `/actuator/prometheus` with `maestro.*` meters, and exports OTLP spans to `$OTEL_EXPORTER_OTLP_ENDPOINT`. Tasks 2 and 3 consume both.

- [ ] **Step 1: Add the two catalog entries.** `micrometer-registry-prometheus = { module = "io.micrometer:micrometer-registry-prometheus" }` and `opentelemetry-exporter-otlp = { module = "io.opentelemetry:opentelemetry-exporter-otlp" }` — both BOM-managed, no version refs (match the existing `micrometer-core` style at lines 69-72).
- [ ] **Step 2: Add dependencies to all three services.** `implementation(libs.spring.boot.starter.actuator)`, `runtimeOnly(libs.micrometer.registry.prometheus)`, `implementation(libs.spring.boot.micrometer.tracing.opentelemetry)`, `runtimeOnly(libs.opentelemetry.exporter.otlp)`.
- [ ] **Step 3: Configure each `application.yml`** — expose the actuator endpoints, enable the Maestro observability flags, point OTLP at an env-var endpoint, and set 100% sampling (a demo must never drop the trace you are pointing at):
```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  tracing.sampling.probability: 1.0
  otlp.tracing.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
maestro:
  observability:
    metrics.enabled: true
    tracing.enabled: true
```
- [ ] **Step 4: Verify locally.** Start the existing loan compose plus the three services, drive one loan, then `curl -s localhost:8091/actuator/prometheus | grep -c '^maestro_'` — expect a non-zero count. Archive the output.
- [ ] **Step 5: Confirm nothing regressed.** `./gradlew build` green; the existing `e2e/run-e2e.sh` still passes unchanged (run it once).
- [ ] **Step 6: Commit** — `feat(demo): actuator, Prometheus and OTLP wiring for the loan services`

## Task 2: The demo stack (compose + Prometheus + Grafana + Jaeger)

**Files:**
- Create: `demo/docker-compose.yml`, `demo/prometheus.yml`, `demo/grafana/provisioning/datasources/prometheus.yml`, `demo/grafana/provisioning/dashboards/dashboards.yml`, `demo/grafana/dashboards/maestro.json`, `demo/docker/init-demo-dbs.sh`, `demo/scripts/start-services.sh`, `demo/scripts/stop-services.sh`
- Test: `demo/.evidence/task-2-*.log`

**Interfaces:**
- Consumes: Task 1's `/actuator/prometheus` and OTLP export.
- Produces: `docker compose -f demo/docker-compose.yml up -d` brings up infrastructure + observability; `demo/scripts/start-services.sh` starts the four host JVMs and writes pid files to `demo/.run/`. Grafana on 3000, Jaeger UI on 16686, Prometheus on 9090, admin on 8080, loan services on 8091-8093. Task 4's runbook drives these.

- [ ] **Step 1: Write the compose file — infrastructure and observability only (seven containers).** One Postgres (databases `loan_application`, `verification_gateway`, `underwriting`, `admin` — created by `init-demo-dbs.sh`, modelled on the existing `docker/init-loan-dbs.sh`), Kafka + `kafka-init` (pre-creating every topic the services and admin need, including `maestro.admin.events`), Valkey, Prometheus, Grafana, Jaeger all-in-one. Each gets an explicit `mem_limit`. The four JVMs (three loan services + admin) run on the host from built jars — see the plan's Process model note — started by `demo/scripts/start-services.sh` with `-Xmx256m` each, writing pid files to `demo/.run/` so the runbook's kill and restart steps are exact. A documented `TWO_NODE=1` env var makes that script start a second loan-application instance on port 8094, off by default.
- [ ] **Step 2: Prometheus scrape config** — 5s interval (a demo needs responsive graphs), targeting the host services at `host.docker.internal:{8091,8092,8093}/actuator/prometheus`. Verify that resolves from inside the Prometheus container on this machine before relying on it; if it does not, fall back to `network_mode: host` for Prometheus and note it in the runbook.
- [ ] **Step 3: Grafana provisioning** — datasource and dashboard auto-loaded from files so nothing is configured by hand on stage.
- [ ] **Step 4: Author the dashboard** with exactly these panels, all sourced from meters that exist (verify each name against `MicrometerEngineObserver` before writing the JSON — do not trust this list):
  - Workflows started / completed / failed (rate)
  - **Workflows parked** (gauge) — the panel the kill-9 scenario points at
  - Activity duration p50/p95 by activity
  - Recovery adoptions (counter)
  - Stand-downs by reason
- [ ] **Step 5: Verify end to end.** Bring the stack up cold, drive one loan, and confirm: Prometheus has the three targets UP; the Grafana dashboard renders non-empty; Jaeger shows a trace spanning all three services. Archive each check.
- [ ] **Step 6: Commit** — `feat(demo): compose stack with Prometheus, Grafana and Jaeger`

## Task 3: The v2 source set — `version()`-guarded `parallel()`

**Files:**
- Create: `maestro-samples/sample-loan-origination/loan-application-service/src/v2/java/io/b2mash/maestro/samples/loan/application/workflow/LoanApplicationWorkflow.java`
- Modify: `maestro-samples/sample-loan-origination/loan-application-service/build.gradle.kts` (v2 source set + `v2BootJar` task)
- Test: `.../src/test/java/.../LoanApplicationWorkflowV2Test.java`, `demo/.evidence/task-3-*.log`

**Interfaces:**
- Consumes: `WorkflowContext.version(String, int, int)` and `parallel(List<Callable<T>>)` from `maestro-core`.
- Produces: `loan-application-v2.jar`. Task 4's runbook stops v1 and starts v2.

- [ ] **Step 1: Write the v2 workflow.** Copy v1 and change only the verification step:
```java
int v = workflow.version("parallel-verification", 1, 2);
if (v == 1) {
    awaitAllVerifications(application);          // v1 behaviour, unchanged
} else {
    workflow.parallel(List.of(
        () -> awaitVerification(application, "credit"),
        () -> awaitVerification(application, "employment"),
        () -> awaitVerification(application, "appraisal")));
}
```
The v1 branch must remain byte-identical in behaviour — an in-flight loan replaying under v2 must take it and produce the same event sequence.
- [ ] **Step 2: Register the source set and jar task** so `./gradlew :…:loan-application-service:v2BootJar` produces `loan-application-v2.jar`, and wire it into `build` so CI compiles v2 and it cannot rot.
- [ ] **Step 3: Write the failing test first** — a test proving a workflow that recorded `VERSION_MARKER` version 1 still takes the sequential branch when replayed against v2 code, and a fresh workflow under v2 takes the parallel branch. Show it RED before the v2 class exists.
- [ ] **Step 4: Make it pass**, then run `./gradlew :maestro-samples:sample-loan-origination:loan-application-service:test` and the full `./gradlew build`.
- [ ] **Step 5: Verify the demo move for real.** With the stack up: start a loan and let it reach verification; stop v1; start v2; confirm the in-flight loan completes on the sequential path while a newly started loan fans out. Capture both Jaeger trace IDs.
- [ ] **Step 6: Commit** — `feat(demo): v2 loan workflow — parallel verification behind workflow.version()`

## Task 4: The runbook

**Files:**
- Create: `demo/RUNBOOK.md`, `demo/scripts/preflight.sh`, `demo/scripts/reset.sh`, `demo/scripts/drive-loan.sh`

**Interfaces:**
- Consumes: everything from Tasks 1-3.
- Produces: numbered scenarios the deck's `DO:` blocks reference by number.

- [ ] **Step 1: `preflight.sh`** — pull images; build all jars (including v2); bring up compose; run `start-services.sh`; verify ports 3000/4318/5433/6380/8080/8091/8092/8093/9090/16686/29093 are free; wait for every container healthy; pre-create topics; drive one throwaway loan end to end; then **print the PID and build fingerprint of each running service** (a prior cycle was fooled by stale JVMs serving probes). Exit non-zero on any failure.
- [ ] **Step 2: `reset.sh`** — return to a clean slate between rehearsals: truncate the Maestro tables, clear the admin database, restart the three services, leave infrastructure up. Must be idempotent and finish in under 30 seconds.
- [ ] **Step 3: `drive-loan.sh`** — one script taking a scenario name (`happy`, `conditions`, `withdraw`, `crash`) that issues the REST calls to drive that path, so the presenter types one short command rather than a `curl` by hand.
- [ ] **Step 4: Write `RUNBOOK.md`** — T-30 pre-flight, then one section per scenario, each with: the exact command, what to point at on screen, what should appear, expected timing, and **a stated fallback sentence** if it does not appear. Scenarios: (1) start a loan, (2) `kill -9` and recover, (3) the Jaeger trace, (4) withdrawal + compensation, (5) Grafana under load, then deep dives D1-D6 per the spec. Ends with teardown.
- [ ] **Step 5: Rehearse it.** Follow the runbook top to bottom exactly as written, from a cold machine, and fix anything that does not work as documented. Archive the transcript.
- [ ] **Step 6: Commit** — `docs(demo): runbook and driver scripts`

## Task 5: The presentation

**Files:**
- Create: `demo/presentation/index.html` (self-contained: inline CSS + JS, no CDN, no network at runtime)

**Interfaces:**
- Consumes: the runbook's scenario numbers.

- [ ] **Step 1: Build the deck shell** — keyboard navigation (arrows, `f` fullscreen, `p` presenter view, `g` jump-to-slide), a presenter window showing current notes plus the next slide, and slides authored as plain HTML sections so editing content needs no tooling.
- [ ] **Step 2: Write the slides.** Opening (the problem, in domain terms from `DOMAIN-BRIEF.md`), the architecture diagram (no central server; three SPIs), the five top scenarios, then the six deep dives as directly-jumpable sections. Each slide carries a `SAY:` block and a `DO:` block naming the runbook scenario number.
- [ ] **Step 3: Include the two code diffs as slides** — the v1→v2 `version()` change, and a minimal workflow showing what a Maestro workflow looks like. Syntax highlighting hand-rolled or omitted; no external library.
- [ ] **Step 4: Verify offline.** Open it with the network disabled and confirm every slide, the presenter view, and all navigation work. Archive the check.
- [ ] **Step 5: Commit** — `docs(demo): self-contained presentation with speaker notes`

## Task 6: QA gate

- [ ] Cold-machine run: `docker compose down -v`, then `preflight.sh`, then the full runbook top to bottom including the v1→v2 deploy and the kill-9 recovery. Everything works as documented or the owning task reopens.
- [ ] `./gradlew build` green; the existing loan `e2e/run-e2e.sh` still passes unchanged.
- [ ] Resource check: record peak container memory during a full run; confirm it fits the ≈4 GB budget, and note the actual figure in the runbook so the presenter knows what the machine needs.
- [ ] Every runbook command executed verbatim as written — no undocumented steps required to make it work.
- [ ] GATE VERDICT to `demo/.evidence/qa-report.md`.

## Dependency graph

Task 1 → Task 2 (the stack scrapes what Task 1 exposes). Task 3 is independent of 1-2 and can be built in parallel by a human, but sequentially here to avoid module conflicts. Task 4 needs 1-3 running. Task 5 needs Task 4's scenario numbers. Task 6 last.
