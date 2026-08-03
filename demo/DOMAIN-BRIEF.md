# Domain Brief — Loan Origination

Companion to `docs/superpowers/specs/2026-08-03-maestro-demo-design.md`.
Purpose: give the presenter enough of the business domain to narrate the demo
confidently, and to explain *why this domain* rather than a toy example.

## What "loan origination" is

The process from a borrower applying for a mortgage to money actually leaving
the lender's account. In the real world it runs for **days to weeks**, involves
**several parties who don't coordinate with each other**, and ends in a
**money movement that is expensive to undo**.

That combination is the point. It is not a contrived example chosen to show
off a workflow engine — it is the canonical shape of the problem workflow
engines exist for, and every awkward property of it maps onto something
Maestro has to get right.

## The actors

| Actor | Behaviour that matters |
|---|---|
| **Borrower** and **co-borrower** | Act independently and asynchronously — either may upload documents or sign at any time, in any order |
| **Credit / employment / appraisal providers** | Third parties with arbitrary latency and no ordering guarantees; may respond in any sequence, and may respond twice |
| **Underwriter** (human) | Approves, rejects, or returns *conditions* — and may simply not answer, which must escalate |
| **Senior underwriter** | Receives escalation when the first underwriter times out |
| **The lender** | Reserves a rate lock and disburses funds — the two steps that cost real money |

## The flow, as implemented

The orchestrator is `LoanApplicationWorkflow` in **loan-application-service**.
Its eight steps:

1. **Validate and record** the application.
2. **Fan out three verification requests** — credit, employment, appraisal —
   onto `loans.verification.requests`.
3. **Fan in the results, in any order.** All three arrive on a *single* signal
   name (`verification.result`); the workflow loops until it has seen all
   three types, tolerating duplicates, arbitrary ordering, and rejecting
   unexpected types. Any declined verification fails the loan.
4. **Collect required documents** — from either borrower, in any order.
5. **Withdrawal gate #1.** The borrower may withdraw. Nothing has been
   reserved yet, so this fails the workflow cleanly with no compensation.
6. **Underwriting rounds, up to three.** Each round awaits a decision whose
   verdict — `APPROVED` / `REJECTED` / `CONDITIONS` — travels *in the
   payload*. `CONDITIONS` means "give me more documents"; the workflow
   collects one document per condition and runs another round. `CONDITIONS`
   on the final round is treated as a rejection. Stale decisions from earlier
   rounds are consumed and ignored.
7. **The funding saga** — reserve rate lock → collect signatures → disburse.
   Signatures are deduplicated by signer, so a borrower clicking twice costs
   nothing. **Withdrawal gate #2** sits immediately before disbursement:
   withdrawing here throws, and saga compensation **releases the rate lock**
   in LIFO order.
8. **Complete.**

Two supporting services:

- **verification-gateway-service** — simulates the three providers, one
  workflow per verification type per loan, plus webhook endpoints. It is the
  source of the deliberately awkward arrival patterns.
- **underwriting-service** — DTI-based auto-assessment rules, a human decision
  queue, and timeout escalation to a senior underwriter.

## Three design idioms the sample teaches

These are the reusable lessons an evaluating engineer takes home, and each is
worth calling out on stage:

1. **Decision-as-payload.** One signal name per decision point; the verdict
   lives in the payload, never in competing signal names. Adding a fourth
   verdict later requires no new signal, no new listener, and no change to
   in-flight workflows.
2. **Withdrawal gates.** Cancellation is honoured at *defined points*, not
   arbitrarily. Where the gate sits determines whether compensation is needed
   — which is a business decision made explicit in code.
3. **Any-order fan-in on one signal name.** Loop `awaitSignal` until the set
   is complete, bounded, tolerating duplicates. This is how you consume real
   third parties, who do not respect your ordering assumptions.

## Why this domain showcases Maestro specifically

| Domain property | What it forces the engine to prove |
|---|---|
| Runs for days; the process outlives any single deployment | Durable state, crash recovery, resume mid-flight |
| Third parties respond out of order, twice, or before you're ready | Signals persisted immediately; orphan adoption; never discard a signal |
| A human may never respond | Durable timers with escalation |
| Rate lock and disbursement move money | Saga compensation, and idempotent activities |
| Three services own their own state | Orchestration within a service, choreography between |
| It will be deployed to mid-flight | Versioning (`workflow.version()`), and safety in mixed-version windows |

## The demo variants

The scenarios in the demo map onto real business situations, which is what
makes them narratable to a non-engineer:

- **Happy path** — approved, signed, disbursed.
- **Conditions loop** — underwriter asks for more paperwork; loan proceeds
  after a second round.
- **Withdrawal after rate lock** — borrower pulls out late; the rate lock is
  released by compensation. *The audience sees money-adjacent cleanup happen
  automatically.*
- **Crash mid-flight** — the process is killed; the loan resumes exactly where
  it was, with completed work never repeated.
- **Deploy mid-flight** (v1→v2) — verification becomes parallel; loans already
  in progress keep their original sequential behaviour.

## Vocabulary for the room

- **Rate lock** — a promise to hold an interest rate for a period; reserving
  one has a real cost, which is why withdrawing after it must compensate.
- **DTI** — debt-to-income ratio, the main auto-assessment input.
- **Conditions** — an underwriter's "yes, if…", requiring further documents.
- **Origination** — everything up to and including funding; servicing the loan
  afterwards is a different system entirely.

## Ports and endpoints (for the runbook)

| Component | Port | Role |
|---|---|---|
| loan-application-service | 8091 | Applications, documents, signatures, withdrawal |
| verification-gateway-service | 8092 | Per-type verification webhooks |
| underwriting-service | 8093 | Underwriter and senior decision endpoints |
| Postgres | 5433 | Databases `loan_application`, `verification_gateway`, `underwriting` |
| Valkey | 6380 | Locks, signal notifications |
| Kafka | 29093 | External listener for host-run services |
