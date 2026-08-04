# Handover prompt — Maestro: close the open issues

Give the block below verbatim to a fresh coordinator session started in the
Maestro repo root. Written 2026-08-04, after the demo cycle merged (PR #33,
`main` @ `60e7789`).

---

Familiarize yourself with this library — read `CLAUDE.md`, then
`docs/open-issues.md` §§1–3 for the mental model and §4 for the issue index.
Then close the open issues below with your team of engineers and architects.
You are the coordinator.

## What's open

Five issues remain in `docs/open-issues.md`. Read each section in full before
planning; every one has a written mechanism, file:line citations, and a "done
when" clause.

| # | Issue | Kind | Severity |
|---|---|---|---|
| 23 | Maestro's Kafka beans silently disable `spring.kafka.*`; `@MaestroSignalListener` drops trace context | Library defect | **Critical** |
| 22 | Compensations can run on an operator-terminated workflow | Library defect | Medium |
| 24 | Redelivery always dead-letters, but nothing creates or documents the `.DLT` topics | Library gap (docs + config) | Medium |
| 16 | Retrying a compensated saga is guarded off, not supported | Library gap | Medium |
| 11, 12 | Fencing tokens; recovery-poll scale | Known limitations, open **by design** | Medium / Low |

**Suggested order.** Issue 23 first — it is the only Critical, it silently
affects every Maestro+Kafka user, and it makes a documented promise false.
Issue 24 next: it is adjacent (Kafka configuration), small, and mostly a
product decision. Then 22, which has a subtlety worth respecting (below).
Leave 11 and 12 alone unless you are explicitly asked — they are open by
deliberate decision and want measurements or an SPI change, not a patch.

## The theme worth naming

**Four defects in the last two cycles were "configuration that reads correctly
and does nothing":** the observability auto-config shipping inert on
auto-config ordering; a Spring Boot 3.x OTLP property name under Boot 4;
Issue 23's bean shadowing; and `version(changeId, 1, 2)` where `DEFAULT_VERSION`
is `-1`.

So consider opening with **an audit rather than a fix**: enumerate every bean
Maestro registers that Spring Boot also provides conditionally, and every
documented property, and check each actually takes effect at runtime. Issue 23
is one instance; the audit tells you whether there are others. A Fable/Opus
architect is the right agent for that, and its output becomes the plan.

## Issue-specific cautions

- **Issue 23** — the fix is more than one line. `maestroKafkaTemplate` shadows
  Boot's `kafkaTemplate` by type (`@ConditionalOnMissingBean(KafkaTemplate.class)`),
  which also voids `spring.kafka.producer.*`, `template.default-topic` and
  `transaction-id-prefix`, not just observation. Separately
  `MaestroSignalListenerBeanPostProcessor` hand-builds `ContainerProperties`
  and never calls `consumeWithTraceContext`. Decide deliberately whether
  Maestro should stop shadowing, or honour the properties itself. The samples
  and `demo/` currently carry an explicit override as a stopgap — **remove it
  when the library fix lands**, or the sample will keep teaching the
  workaround. `docs/observability.md` carries a scope-limit paragraph that
  must be retracted at the same time.
- **Issue 22** — the previous final review upheld deferring this, with an
  argument worth re-reading before you change anything: the bug *occasionally*
  runs compensations that shouldn't run, whereas a rushed fix on that path
  would *silently skip* compensations that should, on every failed saga.
  Issue 21's fix (`InstanceStatusWriter`, bounded retry against a fresh read)
  is the established idiom and needed a mutation round to prove its pins were
  load-bearing. Expect the same rigor.
- **Issue 24** — genuinely a product decision (document `.DLT`, add a startup
  check, and/or add `maestro.messaging.redelivery.enabled`). Rule on it rather
  than picking silently. Both compose files pre-create 11 topics and no `.DLT`
  companion, with auto-create off.

## Also inherited from the demo cycle

In `tasks/todo.md` and `.superpowers/sdd/*/progress.md` (search "deferred"):

- `startRenewerIfNeeded()` sits inside the same `try` that maps a throw to
  `NO_BACKEND` — the identical shape as a bug already fixed once in
  `WorkflowInstanceLockManager`. Cheap, and it closes the class.
- The engine writes an instance row terminal and appends the terminal event as
  two non-transactional calls, so "wait for terminal, then read the log" can
  observe an incomplete log. A `WorkflowStore.finaliseInstance(instance,
  terminalEvent)` with a both-or-neither contract was recommended and NOT
  implemented — **do not** reorder to append-then-status, which is worse
  (two converging runners could both append, and `getRecoverableInstances()`
  would re-invoke a completed workflow).
- `demo/scripts/v1-to-v2-move.sh` hardcodes a versioned jar name; it fails
  loudly and is disclosed, but a version bump breaks the D1 deep dive.

## How to work

1. **Skills first.** Invoke `superpowers:brainstorming` before planning, then
   `superpowers:writing-plans`, then `superpowers:subagent-driven-development`
   and follow it exactly: per-task implementer, an independent reviewer per
   task, fix loops with scoped re-reviews, a ledger in the SDD workspace, a
   final whole-branch review, then
   `superpowers:finishing-a-development-branch` — present the integration menu
   and do not merge without the user's answer.
2. **Isolation.** `EnterWorktree` before any implementation. Base: `origin/main`.
3. **Read `tasks/lessons.md` at session start.** Every entry was paid for.
   The ones that will bite you here: prove a feature fires at *runtime* rather
   than inferring it from configuration; run every new pin against the unfixed
   code first; and never quote a number you cannot grep back out of an
   archived log.
4. **Reviews are adversarial and have repeatedly been decisive** — reviewers on
   this project have reverted fixes in scratch worktrees to prove a test was
   load-bearing, decompiled jars to check a property name, and recomputed
   metrics from raw artifacts. Expect and encourage that.
5. **Agent deaths are frequent and are infrastructure, not defects**
   (fourteen in the demo cycle, none from bad work). Mitigations that work:
   put briefs on disk and keep dispatch prompts short; have implementers write
   a skeleton and commit *before* exploring; commit per item, never per task.
   On death, check the worktree and resume with a precise state summary; after
   two deaths on one step, dispatch a fresh agent over the surviving tree.

## Definition of done

Issues 23, 24 and 22 resolved with RED-first pins and release-note entries
where behaviour changes; the samples' and demo's Issue-23 workaround removed
and `docs/observability.md`'s scope-limit paragraph retracted; `docs/open-issues.md`
updated with Resolved callouts; full `./gradlew build` green; the loan E2E
(`maestro-samples/sample-loan-origination/e2e/run-e2e.sh`) 10/10 and the chaos
PR-gate green; `demo/scripts/preflight.sh` still passes cold, since the demo
depends on the Kafka wiring you are changing. Branch pushed and a PR opened
only after the user picks the integration option.
