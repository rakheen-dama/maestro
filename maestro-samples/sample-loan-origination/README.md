# Maestro Sample — Loan Origination

A three-service demo of Maestro handling a **long-lived, multi-actor workflow
with out-of-order signals**: borrower and co-borrower acting independently,
third-party verifiers with arbitrary latency and ordering, human underwriting
with timeout escalation, and withdrawal honoured at defined gates.

The binding contract for everything in this sample is [SPEC.md](SPEC.md).

## Architecture

```
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

## REST API, workflows, and scenarios

See [SPEC.md](SPEC.md) for the exact REST endpoints, workflow IDs, signal
names, Kafka topics, and the five E2E scenarios. Service builders extend this
README with per-service usage examples.
