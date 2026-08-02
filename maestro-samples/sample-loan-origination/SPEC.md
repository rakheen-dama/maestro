# Loan Origination Sample — Build Contract

This spec is the single source of truth for all agents building this sample.
Deviations require updating this file first. Read the repo `CLAUDE.md` before
writing any code — its conventions (Spring Boot 4 modular starters, Jackson 3
`tools.jackson`, Jakarta EE, JSpecify, no Lombok, Gradle Kotlin DSL) are
mandatory. Follow the structure of `maestro-samples/sample-order-service` and
`sample-payment-gateway` wherever this spec is silent.

## Purpose

A three-service demo of Maestro handling a long-lived, multi-actor workflow
with out-of-order signals:

- **Actors:** borrower, co-borrower (sign/upload independently, any order),
  underwriter, senior underwriter (escalation), third-party verifiers
  (webhook-style, arbitrary latency/order), and "withdraw at any gate".
- **Out-of-order cases exercised:** signal-before-await (fast credit result),
  signal-before-workflow-exists (webhook/doc upload racing workflow creation —
  orphan adoption), signal-while-service-down (recovery replay).

## Modules

```text
maestro-samples/sample-loan-origination/
├── loan-application-service/      ← orchestrator + funding saga (port 8091)
├── verification-gateway-service/  ← simulated credit/employment/appraisal (port 8092)
├── underwriting-service/          ← auto-rules + human decision queue (port 8093)
├── docker-compose.yml             ← Postgres (per-service DBs), Kafka, Valkey
├── e2e/run-e2e.sh                 ← end-to-end scenario driver (curl + assertions)
├── SPEC.md                        ← this file
└── README.md
```

Root `settings.gradle.kts` gains three includes:
`maestro-samples:sample-loan-origination:loan-application-service`, etc.
Each module uses the same convention plugin as existing samples
(`maestro.spring-app-conventions`) and depends on `maestro-spring-boot-starter`,
`maestro-store-postgres`, `maestro-messaging-kafka`, `maestro-lock-valkey`,
plus `maestro-test` (testImplementation).

Packages: `io.b2mash.maestro.samples.loan.application`,
`...loan.verification`, `...loan.underwriting`.

## Kafka topics (pre-created in docker-compose init, NEVER auto-created)

| Topic | Producer → Consumer | Key |
|---|---|---|
| `loans.verification.requests` | loan-application → verification-gateway | loanId |
| `loans.verification.results` | verification-gateway → loan-application | loanId |
| `loans.underwriting.requests` | loan-application → underwriting | loanId |
| `loans.underwriting.decisions` | underwriting → loan-application | loanId |

Also pre-create `maestro.signals.loan-application`,
`maestro.signals.verification-gateway`, `maestro.signals.underwriting`
(engine-level inbound signal channels; the starter subscribes automatically).

Event DTOs are records serialized with Jackson 3; every event carries `loanId`.

## Workflow IDs and signal names (exact strings — the cross-service contract)

| WorkflowId | Where |
|---|---|
| `loan-{applicationId}` | loan-application-service |
| `verification-{loanId}-{type}` (type ∈ credit, employment, appraisal) | verification-gateway |
| `underwriting-{loanId}-round{n}` (n = 1-based review round) | underwriting-service |

Signals delivered INTO `loan-{id}` (via `@MaestroSignalListener` on the topics
above, or REST → `MaestroClient` for human actions):

| Signal name | Payload record | Source |
|---|---|---|
| `verification.result` | `VerificationResult(loanId, type, approved, details)` | results topic |
| `document.uploaded` | `DocumentUploaded(loanId, docType, uploadedBy)` | REST |
| `underwriting.decision` | `UnderwritingDecision(loanId, round, verdict, conditions)` — verdict ∈ APPROVED, REJECTED, CONDITIONS | decisions topic |
| `package.signed` | `Signature(loanId, signerId)` | REST |
| `application.withdrawn` | `Withdrawal(loanId, reason)` | REST |

Signals INTO `underwriting-{loanId}-round{n}`:

| Signal name | Payload | Source |
|---|---|---|
| `underwriter.decision` | `Decision(verdict, conditions)` | REST |
| `senior.decision` | `Decision(verdict, conditions)` | REST (after escalation) |

**Design idioms (mandatory, document them in code comments):**
1. **Decision-as-payload:** one signal name per decision point; the verdict
   lives in the payload. Never model approve/reject as competing signal names.
2. **Withdrawal gates:** withdrawal is honoured at two gates (before
   underwriting submission; before disbursement). At each gate the workflow
   does `awaitSignal("application.withdrawn", Withdrawal.class, gateTimeout)`
   inside try/catch of `SignalTimeoutException`; timeout = not withdrawn,
   continue. A pre-arrived withdrawal is consumed instantly (no delay). Gate
   timeout is a property, default 1s.
3. **Any-order fan-in:** verification results use ONE signal name; the
   workflow loops `awaitSignal("verification.result", ...)` until all three
   types have been seen (bounded at 10 iterations), tolerating duplicates and
   any arrival order. (`@MaestroSignalListener` binds one signalName per
   method — do not attempt per-type signal names on a shared topic.)

## LoanApplicationWorkflow (the long one)

Input: `LoanApplication(applicationId, borrowerIds[1..2], amount, income, propertyValue, requiredDocs)`.

1. Activity `recordApplication` — validate, persist demo state.
2. Activity `requestVerifications` — publish one request per type to
   `loans.verification.requests`.
3. Collect verification results (idiom 3). Any `approved=false` → workflow
   FAILS with reason (no saga yet — nothing to compensate).
4. Collect required documents: `collectSignals("document.uploaded",
   DocumentUploaded.class, requiredDocs.size(), docTimeout)`.
5. **Withdrawal gate #1** (idiom 2). Withdrawn → throw `LoanWithdrawnException`.
6. Loop (max 3 rounds): activity `requestUnderwriting(round)` → publish to
   `loans.underwriting.requests`; `awaitSignal("underwriting.decision", ...)`.
   - APPROVED → break. REJECTED → workflow fails with reason.
   - CONDITIONS → `collectSignals("document.uploaded", ...,
     conditions.size(), docTimeout)` then next round. Round 3 CONDITIONS →
     treat as REJECTED.
7. Funding (saga — annotate method `@Saga`):
   - Activity `reserveRateLock` with `workflow.compensation(releaseRateLock)`
     (use the same compensation API style as `sample-payment-gateway`).
   - `collectSignals("package.signed", Signature.class, borrowerIds.size(),
     signTimeout)` — assert each borrower signed exactly once (dedupe by
     signerId; extra/duplicate signatures collect additional signals up to a
     bound of 5 total).
   - **Withdrawal gate #2**. Withdrawn → throw (compensation releases lock).
   - Activity `disburse` with compensation `reverseDisbursement`.
8. Return `LoanResult(applicationId, "FUNDED", ...)`.

All timeouts are `maestro.sample.*` properties (Duration) with fast defaults
for demo (docTimeout 10m, signTimeout 10m, decisionTimeout 10m, gateTimeout 1s)
and overridden to ≤1s in tests. NO nondeterminism in workflow code: use
`workflow.currentTime()` / `workflow.randomUUID()` only.

REST API (thin controllers → MaestroClient):
- `POST /applications` `{applicationId?, borrowerIds, amount, income, propertyValue, requiredDocs}` → starts `loan-{id}`, returns id.
- `POST /applications/{id}/documents` `{docType, uploadedBy}` → signal.
- `POST /applications/{id}/sign` `{signerId}` → signal.
- `POST /applications/{id}/withdraw` `{reason}` → signal.
- `GET  /applications/{id}` → status from the store (instance status + output), like existing samples' status endpoints.

## VerificationWorkflow (verification-gateway)

`@KafkaListener` on `loans.verification.requests` starts
`verification-{loanId}-{type}` (`startAsync`, idempotent: catch
already-exists). Workflow: `workflow.sleep(simulatedLatency(type))` (property,
default credit 2s / employment 5s / appraisal 8s) → activity
`callProvider` — deterministic simulated outcome: approved unless the loan
amount ends in `13` (repeatable demo failure), with a `@RetryPolicy`-decorated
simulated flaky call (fails first 2 attempts when amount ends in `7`) → activity
`publishResult` to `loans.verification.results`.
Also expose `POST /webhooks/{type}/{loanId}` `{approved, details}` which
signals `verification.result` DIRECTLY into `loan-{loanId}` via
`WorkflowMessaging.publishSignal` or a `MaestroClient` remote-signal REST call
to loan-application — pick the simplest working mechanism and document it —
to demonstrate pre-delivery/orphan adoption in the E2E.

## UnderwritingWorkflow (underwriting-service)

`@KafkaListener` on `loans.underwriting.requests` starts
`underwriting-{loanId}-round{n}`. Workflow:
1. Activity `autoAssess`: DTI = amount / income. DTI < 3 and all verifications
   approved → auto-APPROVE. DTI > 6 → auto-REJECT. Else → human queue.
2. Human path: `awaitSignal("underwriter.decision", Decision.class,
   underwriterTimeout)`; on `SignalTimeoutException` → activity
   `escalate` (log) → `awaitSignal("senior.decision", Decision.class,
   seniorTimeout)`; a second timeout → REJECTED("no decision").
3. Activity `publishDecision` to `loans.underwriting.decisions` (carries the
   round from the workflow input).

REST: `POST /underwriting/{loanId}/rounds/{n}/decision` and
`.../senior-decision` `{verdict, conditions}` → MaestroClient signal.
`GET /underwriting/pending` optional nicety.

## Test requirements (every builder delivers these green)

Per service, `TestWorkflowEnvironment`-based tests (fast timeouts):
- **loan-application:** happy path E2E-in-memory (all signals in order);
  verification results in REVERSE order and with a duplicate; documents
  pre-delivered BEFORE workflow start (`preDeliverSignal` — orphan adoption);
  conditions loop (2 rounds); withdrawal at gate #1 (no compensation events)
  and at gate #2 (rate-lock compensation runs — assert compensation event);
  signature dedupe (same signer twice + second signer); underwriting REJECTED.
- **verification-gateway:** result published after sleep (use test clock
  advance); flaky-provider retry path; deterministic decline (amount …13).
- **underwriting:** auto-approve; auto-reject; human decision; escalation on
  timeout then senior decision; double-timeout reject.

Activities that publish to Kafka must be behind interfaces so tests can use
in-memory fakes (same pattern as existing samples' messaging activities).

## E2E scenario (e2e/run-e2e.sh, run by the verifier agent)

Prereqs: `docker compose up -d` (wait for health), then `bootRun` all three
services (background, logs to files). Script uses curl + jq, asserts via
`GET /applications/{id}`:
1. **Happy path:** create app (2 borrowers, DTI human-range) → upload docs →
   underwriter approves → both sign (co-borrower first!) → status FUNDED.
2. **Out-of-order:** upload a document BEFORE creating the application
   (webhook to a predetermined id) → create app → verify doc was adopted.
3. **Conditions loop:** underwriter returns CONDITIONS → upload extra doc →
   approve on round 2 → FUNDED.
4. **Withdrawal after rate lock:** approve → withdraw before signing →
   status FAILED and log shows rate-lock compensation.
5. **Crash recovery:** start an app, kill loan-application-service (kill -9)
   while awaiting underwriting decision, restart it, send the decision →
   FUNDED. (Proves recovery replay + signal-while-down.)

**Scenarios 6-10** (multi-node — added by the multi-instance verification
cycle: two-node loan-application, owner-kill peer adoption, rolling restart,
timer-poller leader failover, cross-node admin retry/terminate) are
documented in the header comment of `e2e/run-e2e.sh` and in `README.md`,
not duplicated here.

## Library-bug protocol

If the sample exposes an engine defect: reproduce it FIRST as a failing test
in the owning library module (maestro-core / starter / messaging-kafka / ...),
then fix the library, then re-run the sample. Never work around a proven
engine bug inside the sample without flagging it.
