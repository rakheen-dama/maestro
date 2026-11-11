# Host-safety cycle — spec

**Date:** 2026-11-11 · **Base:** main @ a27a275 · **Source:** the 2026-11-11
three-track read-only audit (host impact, engine correctness, release readiness).
Nothing in the audit was executed; every load figure below is arithmetic from the
code's intervals.

## Why

Maestro's claim is "add the starter and a database blip or a deploy no longer
loses work". Two findings contradict it for a host that already runs Postgres:

1. **A transient store error on an active workflow is recorded as a business
   failure.** `AbstractJdbcWorkflowStore` wraps every `SQLException` in the
   package-private `UncheckedSqlException` (`maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java:1709`).
   `maestro-core` cannot classify it, so it lands in `catch (Exception e)` →
   `failAndCompensate` (`WorkflowExecutor.java:2350`). `charge()` succeeds, the
   insert recording it hits a pool timeout, the saga compensates the earlier
   steps and writes `FAILED`. `docs/failure-scenarios.md` S2.3 expects a
   stand-down. Only parked probes are protected today (`ParkProbe`, Issue 20).
2. **The engine's load on the host's database and pool is unbounded by default.**
   - Lock renewal is one `UPDATE` per held lock every 10 s on one thread
     (`WorkflowInstanceLockManager.java:284-297`); once a pass exceeds ~20 s the
     30 s TTL lapses and peers adopt en masse.
   - `getRecoverableInstances()` has no limit and no service filter and loads
     `input`/`output` for every active row, on every node, every 60 s
     (`AbstractJdbcWorkflowStore.java:309-316`); each row not running locally
     then costs one lock statement.
   - `maestro.worker.task-queues` defaults to empty = unbounded
     (`MaestroProperties.java:441`), so a deploy replays every active workflow
     at once through the host's Hikari pool.
   - Every Maestro bean injects the host's `DataSource` by type with no way to
     give the engine its own pool (`PostgresStoreAutoConfiguration.java:94,121`).

## Requirements

- **R1** A store fault that is not caused by the workflow's own data never
  records `FAILED`, never runs a compensation, never records
  `COMPENSATION_STEP_FAILED`. The local run is abandoned, the instance stays
  active, and ordinary recovery resumes it.
- **R2** The abandon is sticky: workflow code that catches the exception cannot
  carry on down a different path.
- **R3** A fault on an activity-outcome append is retried briefly in place
  before abandoning, because the side effect has already happened.
- **R4** Parked probes keep failing open exactly as today (Issue 20 unchanged).
- **R5** A store error caused by the workflow's data (SQLSTATE class 22 or 23)
  still fails the workflow as today.
- **R6** Lock renewal costs one statement per 500 held locks per pass.
- **R7** A recovery pass reads only this service's active instances, in pages,
  and does not issue one lock statement per instance another node holds.
- **R8** Workflow and activity concurrency are bounded per node by default,
  with an explicit opt-out.
- **R9** A host can give Maestro a dedicated `DataSource` with one annotation.
- **R10** Every change is rolling-deploy safe against a node on a27a275 and
  needs no hand-applied migration to keep working.

## Out of scope (named so nobody assumes they are covered)

Issue 30 (recovery admission gated on capacity; startup recovery off the
`ApplicationRunner` thread); the `pendingWakes` leak (`ParkingLot.java:252`);
admin retry deleting caught `ACTIVITY_FAILED` memos; Kafka bean shadowing;
health-probe and shutdown-phase changes; DB-clock timers; public/internal API
split; every Maven Central item.

## Decisions taken here (challenge them in review)

- **D1** Service filter on recovery is unconditional. A service whose
  `maestro.service-name` is renamed while workflows are active strands them
  until the name is restored. Stated in the release notes; no escape property.
- **D2** Default bounds: 20 workflow runs and 20 live activities per task queue
  per node. `0` means unbounded.
- **D3** Retry budget for R3 is fixed, not configurable: 3 attempts, waits of
  200 ms then 1 s.
- **D4** Schema errors (SQLSTATE class 42, e.g. a migration not applied) count
  as store faults: abandon and retry every recovery pass, loudly, rather than
  failing every workflow.
