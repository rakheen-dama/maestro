# Host-safety cycle (2026-11-11)

Spec: [00-spec.md](00-spec.md). Two plans, independent of each other:

| Plan | Branch | Closes |
|---|---|---|
| [01-store-fault-stand-down.md](01-store-fault-stand-down.md) | `fix/store-fault-stand-down` | R1–R5: a database blip no longer fails and compensates a workflow |
| [02-bounded-host-load.md](02-bounded-host-load.md) | `feat/bounded-host-load` | R6–R9: batched lock renewal, paged recovery scan, default concurrency bound, `@MaestroDataSource` |

Order: both need PR #47 (Testcontainers 2.0.5) merged first, or their CI gates
cannot run. They can be built in parallel worktrees. Both edit
`WorkflowExecutor.java` (01: the constructor and catch arms; 02: `recoveryPass`
and the governor wiring) and `docs/release-notes.md`, so merge 01 first and
rebase 02.

Status: planned, awaiting review. Nothing implemented.
