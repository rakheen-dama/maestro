# Maestro Sample — Loan Origination

A three-service demo of Maestro handling a **long-lived, multi-actor workflow
with out-of-order signals**: borrower and co-borrower acting independently,
third-party verifiers with arbitrary latency and ordering, human underwriting
with timeout escalation, and withdrawal honoured at defined gates.

The binding contract for everything in this sample is [SPEC.md](SPEC.md).

## Architecture

```text
                         ┌──────────────────────────────┐
  REST (docs, sign,      │  loan-application-service     │
  withdraw) ──────────►  │  LoanApplicationWorkflow      │
                         │  orchestrator + funding saga  │
                         └───────┬───────────────▲──────┘
        loans.verification.requests│               │loans.verification.results
        loans.underwriting.requests│               │loans.underwriting.decisions
                ┌──────────────────┴───┐   ┌───────┴──────────────────┐
                ▼                      │   │                          │
  ┌──────────────────────────────┐     │   │     ┌──────────────────────────────┐
  │ verification-gateway-service │─────┘   └─────│ underwriting-service          │
  │ simulated credit/employment/ │               │ auto-rules + human decision   │
  │ appraisal + webhooks         │               │ queue + senior escalation     │
  └──────────────────────────────┘               └──────────────────────────────┘
```

- **loan-application-service** — orchestrator: records the application, fans
  out verification requests, collects any-order verification results and
  document uploads, runs up to three underwriting rounds, then a funding saga
  (rate lock → signatures → disburse) with compensation on withdrawal/failure.
- **verification-gateway-service** — simulated credit, employment, and
  appraisal providers (one workflow per verification type per loan), plus
  webhook endpoints that demonstrate signal pre-delivery / orphan adoption.
- **underwriting-service** — auto-assessment rules (DTI-based), a human
  decision queue, and timeout escalation to a senior underwriter.

Out-of-order cases exercised: signal-before-await, signal-before-workflow-exists
(orphan adoption), and signal-while-service-down (crash recovery replay).

## How to run

Start the shared infrastructure (Postgres, Kafka, Valkey — all topics are
pre-created by the `kafka-init` container; Maestro never auto-creates topics):

```bash
cd maestro-samples/sample-loan-origination
docker compose up -d
```

Then run the three services (each in its own terminal, from the repo root):

```bash
./gradlew :maestro-samples:sample-loan-origination:loan-application-service:bootRun
./gradlew :maestro-samples:sample-loan-origination:verification-gateway-service:bootRun
./gradlew :maestro-samples:sample-loan-origination:underwriting-service:bootRun
```

Run the end-to-end scenarios (once all three services are up):

```bash
./e2e/run-e2e.sh
```

## Ports

Host ports are offset from the repo-root `docker-compose.yml` so both stacks
can run side by side.

| Component | Host port | Notes |
|---|---|---|
| loan-application-service | 8091 | REST API for applications, documents, signatures, withdrawal |
| verification-gateway-service | 8092 | Webhook endpoints per verification type |
| underwriting-service | 8093 | Underwriter / senior decision endpoints |
| Postgres | 5433 | Databases: `loan_application`, `verification_gateway`, `underwriting` |
| Valkey | 6380 | Locks and signal notifications |
| Kafka | 29093 | External listener for host-run services |

`E2E_CLUSTER=1 ./e2e/run-e2e.sh` runs a second instance of every service for
the whole run (6 processes) — same `maestro.service-name`/consumer
group/store per service pair, ports offset by +3: loan-application 8094,
verification-gateway 8095, underwriting 8096. See the port-allocation
comment at the top of `e2e/run-e2e.sh`.

`E2E_LOCK_BACKEND=postgres ./e2e/run-e2e.sh` boots every service with
`maestro-lock-postgres` instead of the default `maestro-lock-valkey`, with
the effective backend runtime-verified from each service's own boot log
(not just the property that requested it). See
`.superpowers/sdd/multi-instance/evidence/task5/backend-timings.md` for a
timing comparison of the two backends across the multi-node scenarios below.

**Schema footprint note.** All three services now depend on both
`maestro-lock-valkey` and `maestro-lock-postgres` (to make the switch above
possible), so Flyway's default classpath scan picks up
`V100__maestro_lock_postgres.sql` regardless of which backend is configured.
A **default (Valkey) boot now also creates the `maestro_distributed_lock` and
`maestro_leader_election` tables** in all three sample databases — unused and
inert when `maestro.lock.type` is `valkey` (the default), since nothing reads
or writes them in that mode. If you're diffing this sample's schema against
an older version and see two new empty tables on a Valkey-only run, this is
why.

## REST API, workflows, and scenarios

See [SPEC.md](SPEC.md) for the exact REST endpoints, workflow IDs, signal
names, Kafka topics, and the original five single-node E2E scenarios.
`e2e/run-e2e.sh` covers ten scenarios in total — the original five plus five
added by the multi-instance verification cycle (two-node loan-application,
owner-kill peer adoption, rolling restart, timer-poller leader failover, and
cross-node admin retry/terminate) — see the scenario list in the header
comment of `e2e/run-e2e.sh` for the full, current set. Service builders
extend this README with per-service usage examples.
