# Task 1 Report — RabbitMQ removal

**Identity**
- pwd: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening`
- git HEAD: `722c96e8db715938fbf742de3eb0f3d979725bb4`
- branch: `worktree-release-hardening`
- timestampUtc: `2026-08-02T13:35:45Z`

Evidence artifacts (each embeds its own pwd/HEAD/branch/timestamp header):
- `.superpowers/sdd/release-hardening/evidence/task1/gradlew-projects.log`
- `.superpowers/sdd/release-hardening/evidence/task1/rg-rabbitmq-sweep.log`
- `.superpowers/sdd/release-hardening/evidence/task1/gradle-build-full.log` (fresh `clean build`)
- `.superpowers/sdd/release-hardening/evidence/task1/gradle-build-second-pass.log` (the genuine from-scratch run, real Testcontainers execution, captured before the `clean build`)
- `.superpowers/sdd/release-hardening/evidence/task1/coverage-gate.log`

---

## 1. What was done

1. Deleted the two modules wholesale (`git rm -r`): `maestro-messaging-rabbitmq/` and `maestro-samples/sample-rabbitmq-order-service/` — code, tests, build files, docker-compose, everything.
2. Removed their two `settings.gradle.kts` includes and proved the module graph with `./gradlew projects` (20 projects remain, none named rabbitmq).
3. Checked `build-logic/` and the root `build.gradle.kts` coverage gate: the gate (`verifyModuleTestCoverage`) enumerates modules dynamically (`subprojects.filter { it.name.startsWith("maestro-") }`) and its `modulesWithoutTests` allowlist was already empty — no hardcoded reference to the rabbitmq module existed anywhere in the gate or `libraryModules` (aggregate Javadoc) list, so no gate edit was needed. Confirmed post-removal: `Test-coverage gate: 11 modules with production code, 0 accepted without tests.`
4. Checked `.github/workflows/*.yml`: none reference RabbitMQ by name or step; `build-test.yml` and `release.yml` just run `./gradlew build`, which now naturally excludes the deleted modules. No workflow edits were needed.
5. Swept every remaining live doc/code reference to RabbitMQ and fixed each one (see file list below).
6. Removed the now-dead RabbitMQ-only `deadLetterExchange` field from `MaestroProperties.RedeliveryProperties` (its only consumer was the deleted module) and updated the one test that asserted its binding.
7. Added a `### Removed` entry to `docs/release-notes.md`'s `Unreleased` section with rationale, without touching any pre-existing history entries.
8. Ran the full `./gradlew build` clean (real, from-scratch execution — Testcontainers Postgres/Kafka suites actually ran) and confirmed `BUILD SUCCESSFUL`.
9. Committed in two checkpoints (deletion, then sweep/cleanup).

## 2. Files touched, and why

**Deleted (via `git rm -r`):**
- `maestro-messaging-rabbitmq/` (entire module — build file, main + test sources, autoconfig imports)
- `maestro-samples/sample-rabbitmq-order-service/` (entire module — build file, docker-compose, all sources)

**Build wiring:**
- `settings.gradle.kts` — removed the two `include(...)` entries.

**Code (dead-config cleanup, scoped to the removal):**
- `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java` — removed the `deadLetterExchange` field/param from `RedeliveryProperties` (only the deleted RabbitMQ module read it — `RabbitMqWorkflowMessaging`/`RabbitMqMessagingAutoConfiguration`); updated its Javadoc; fixed the `SignalProperties.wakeRecheckInterval` Javadoc's "(Kafka or RabbitMQ messaging without Valkey)" → "(Kafka messaging without Valkey)".
- `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/MaestroPropertiesBindingTest.java` — removed the `dead-letter-exchange` property assertion (2 spots: the custom-value binding test and the defaults test), since the field no longer exists.
- `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalManager.java` — same "(Kafka or RabbitMQ messaging without Valkey)" Javadoc fix.

**Docs (live/current-state references fixed):**
- `README.md` — pitch line, module table row, samples table row.
- `CLAUDE.md` (repo root) — module-structure tree, package-naming list, Java 21+ → 25+ (three spots: tech-stack table, "Core Design" step 1, "Coding Standards" bullet).
- `CONTRIBUTING.md` — module-overview table row.
- `maestro-samples/README.md` — removed the whole `## sample-rabbitmq-order-service` section and the "no RabbitMQ" clause in the postgres-only sample's bullet list.
- `docs/configuration.md` — `maestro.messaging.type` supported-values list; redelivery property table ("All transports" → "Both transports", dropped the RabbitMQ-only `dead-letter-exchange` row); the Postgres/RabbitMQ stall-queue sentence; the dead-letter destination table's RabbitMQ row; the entire `### RabbitMQ Messaging` subsection; the Backend Comparison table's third column.
- `docs/maestro-architecture.md` — mermaid diagram node `MSG_RMQ`, module table row, the DLQ sentence in §5, the DLQ sentence in the redelivery section.
- `docs/cross-service.md` — the intro note ("Kafka-based... For RabbitMQ or Postgres..." → "...For the Postgres messaging alternative...").
- `docs/getting-started.md` — the `type: kafka # Also supports: postgres, rabbitmq` comment.
- `docs/maestro-prd.md` — the "Now available" backlog-resolution note.
- `docs/self-recovery.md` — the "regardless of your choice of messaging backend (Kafka, Postgres, or RabbitMQ)" sentence.
- `docker-compose.yml` (root) — comment block pointing at the now-deleted sample's docker-compose file.
- `docs/open-issues.md` — **two** spots only, both in the orientation section (§1, "read once to build a mental model", not the dated issue log): the `WorkflowMessaging` SPI-implementations table row, and the module-map code block's `maestro-messaging-rabbitmq` line.
- `docs/test-plan.md` — **one** spot only: the top-of-file "Must-work integrations" scope line ("RabbitMQ/Postgres-messaging are secondary" → "Postgres-messaging is secondary").
- `docs/release-notes.md` — added a new `### Removed` entry under `## Unreleased` (rationale: per-transport verification cost, evidenced by the multi-instance cycle; `WorkflowMessaging` SPI untouched, community adapters remain possible). No existing entry was rewritten.

## 3. Left untouched, and why (historical/planning records)

Per the spec's own §3 invariant plus explicit task-context carve-outs, these keep their RabbitMQ mentions:

| File | Why |
|---|---|
| `docs/test-plan.md` (remaining hits) | Explicitly named historical planning doc in task context: "leave historical mentions unless present-tense support claim." Remaining hits are dated `Fixed`/status-table entries about a defect found and closed in a past release — not claims that RabbitMQ is currently supported. |
| `docs/open-issues.md` (remaining hits) | Same treatment extended by me to this file for consistency: it's a dated, append-in-place issue log (`Status date:`, `Updated:`, `Updated again:` headers; `> Resolved.` callout blocks with commit hashes). Every remaining hit is inside a closed issue's historical narrative (Issue 1, Issue 10, the priority-order writeup) — not a current-architecture claim. Only the two structural/orientation-section spots were fixed (see above). **Flagging this as a judgment call**: the task context named only `test-plan.md` and `multi-instance-test-plan.md` as exempt; I applied the same reasoning to `open-issues.md` because it's structurally identical (dated, resolved-in-place log) and rewriting its historical issue narratives would mean editing commit-hash-backed history, which the brief's spirit ("never rewrite history entries") argues against. A reviewer could reasonably ask for these scrubbed too — they're all inside `> Resolved.` blocks, so scrubbing would not lose any live information. |
| `maestro-integration-tests/SPEC.md` (1 hit) | The "Open items" list entry is a resolved-in-place decision log (`~~...~~ — done.`) describing what was true when Issue 1 was fixed (three transports existed then); not a current claim. |
| `docs/release-hardening-spec.md` | This is the plan document that specifies this very removal — of course it names RabbitMQ throughout. Not in the brief's "Modify" list; editing it would be rewriting the spec that commissioned the work. |
| `docs/release-notes.md` (pre-existing entries) | Explicit instruction: only add the new entry, never rewrite history. |
| `tasks/todo.md`, `tasks/release-hardening-plan.md`, `tasks/release-readiness-plan.md` | Explicit instruction (todo.md) plus the same reasoning extended to the other two: process/planning trackers for past and current cycles, not in the brief's file list, not owned by this task. |
| `.superpowers/sdd/multi-instance/**` | Explicit "Do NOT edit" in both the brief and task context. (For the record: `rg` found **zero** RabbitMQ hits under `.superpowers/` at all — the archived evidence never happened to mention it.) |
| git history | Immutable by construction; not edited. |

## 4. Final `rg -i rabbitmq --files-with-matches` output and classification

```
$ rg -il rabbitmq --hidden -g '!.git' | sort
docs/open-issues.md                    → historical-allowed (dated issue log; only structural spots fixed)
docs/release-hardening-spec.md         → historical-allowed (the spec commissioning this removal)
docs/release-notes.md                  → historical-allowed (pre-existing entries) + 1 new "Removed" entry (intentional)
docs/test-plan.md                      → historical-allowed (dated status log; live scope line fixed)
maestro-integration-tests/SPEC.md      → historical-allowed (resolved-in-place decision log)
tasks/release-hardening-plan.md        → historical-allowed (this task's own plan doc, out of file remit)
tasks/release-readiness-plan.md        → historical-allowed (prior-cycle plan doc)
tasks/todo.md                          → historical-allowed (prior-cycle task tracker, explicit no-rewrite instruction)
```

No hit is a live, present-tense claim that Maestro currently ships RabbitMQ support. `.superpowers/sdd/multi-instance/**` archived evidence has zero hits (confirmed separately). git history is untouched.

## 5. Build result

**Module graph** (`./gradlew projects`, post-deletion): 20 projects, `maestro-messaging-rabbitmq` and `maestro-samples:sample-rabbitmq-order-service` absent; `BUILD SUCCESSFUL`.

**Coverage gate** (`./gradlew verifyModuleTestCoverage`):
```
> Task :verifyModuleTestCoverage
Test-coverage gate: 11 modules with production code, 0 accepted without tests.

BUILD SUCCESSFUL in 898ms
```

**Full build — genuine from-scratch execution** (real Testcontainers Postgres/Kafka runs, captured before the `clean` pass below; `104` of `134` tasks actually executed):
```
BUILD SUCCESSFUL in 1m 43s
134 actionable tasks: 104 executed, 16 from cache, 14 up-to-date
```
Exit code: `0`.

**Full build — `./gradlew clean build` re-run** (confirms the clean-tree green state; most task outputs reused from Gradle's local build cache since no relevant inputs changed between the two runs — `maestro-integration-tests:test` and `maestro-messaging-kafka:test` both show `FROM-CACHE`, which is a legitimate cache hit, not a skip):
```
BUILD SUCCESSFUL in 3s
153 actionable tasks: 87 executed, 57 from cache, 9 up-to-date
```
Exit code: `0`.

## 6. Commits

```
722c96e docs+cleanup: sweep RabbitMQ references after module removal
8f8a36d remove: delete maestro-messaging-rabbitmq and sample-rabbitmq-order-service modules
```
Both on `worktree-release-hardening`, both signed off by the same author as prior branch commits, no `--no-verify`/`--no-gpg-sign` used.

## 7. Self-review against the brief, line by line

Brief (`task-1-brief.md`) checklist, verified against the actual diff:

- [x] **Delete both modules and settings.gradle.kts includes; `./gradlew projects` clean** — done; evidence log attached.
- [x] **Sweep `rg -i rabbitmq --files-with-matches`, fix every hit outside release notes/git history/archived evidence; re-run until spec §3 invariant holds** — done. One deviation worth flagging: I extended the "historical, don't rewrite" treatment to `docs/open-issues.md` and `maestro-integration-tests/SPEC.md`, which the task context didn't name explicitly (it named only `docs/test-plan.md` and `docs/multi-instance-test-plan.md`). I judged them structurally identical (dated, resolved-in-place logs) and explained the reasoning in §3 above — flagging as a call a reviewer may want to revisit, since the spec's literal §3 wording ("returns only: release notes, git history, and archived evidence") doesn't carve these out.
- [x] **CLAUDE.md platform truth pass: Java 25+, Boot 4+, module table updated** — done (3 Java-21+-references fixed; Boot 4.x line was already correct/generic and left as-is; module table + package list updated).
- [x] **Release-notes entry: removal + rationale** — done, new `### Removed` section under `## Unreleased`.
- [x] **Full `./gradlew build` green; commit** — done, both a genuine full run and a `clean build` re-run are green; committed.

**Things done beyond the brief's literal file list** (flagging per self-review instruction):
- Removed the `deadLetterExchange` field from `MaestroProperties.RedeliveryProperties` and updated its binding test. The brief's "Modify" list doesn't name `maestro-spring-boot-starter`, but the field was RabbitMQ-only dead config after the module's deletion (verified via `grep` — no other consumer existed), and leaving it would mean a user could set `maestro.messaging.redelivery.dead-letter-exchange` and have it silently do nothing. Treated as in-scope "removal only" cleanup, not a new interface.
- Fixed `docker-compose.yml` (root) — not named in the brief's file list, but it referenced the now-deleted sample's own docker-compose file by path; leaving it would be a dangling reference.
- Fixed `maestro-core/src/main/java/.../SignalManager.java` and the `SignalProperties` Javadoc in `MaestroProperties.java` — not named in the brief, but both are genuine RabbitMQ mentions in shipped Javadoc that the `rg` sweep surfaces.
- Wrote evidence logs to `.superpowers/sdd/release-hardening/evidence/task1/` (the brief's Global Constraints mandate this directory for evidence artifacts; the brief itself didn't spell out log filenames).

No functional/behavioral change was made beyond the RabbitMQ removal and its direct fallout (dead config field). `WorkflowMessaging` SPI itself: unchanged, as required.
