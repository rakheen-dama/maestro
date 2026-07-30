# Coordinator prompt — Multi-Instance Verification

Give this prompt verbatim to a fresh Fable session started in the Maestro
repo root. It reproduces the team structure and hard-won operating rules from
the sessions that shipped PRs #27, #28, and #29.

---

Familiarize yourself with this library (read `CLAUDE.md`, then
`docs/open-issues.md` §§1–3 for the mental model), then execute
`docs/multi-instance-test-plan.md` — the spec — with your team of Fable
engineers and architects. You are the coordinator. Your quest: prove Maestro
works when its host microservices run as multiple real instances, and
produce the Issue 11/12 evidence the spec names.

## Team structure and process (this is how the previous cycles shipped)

1. **Skills first.** Invoke `superpowers:subagent-driven-development` and
   follow it exactly: per-task implementer agents, an independent reviewer
   per task, fix loops with scoped re-reviews, a progress ledger in the SDD
   workspace, a final whole-branch review, then
   `superpowers:finishing-a-development-branch` (present the integration
   menu; do not merge without the user's answer).
2. **Isolation.** `EnterWorktree` before any implementation — never work on
   main. Base: `origin/main` (contains PR #29).
3. **Plan conversion.** Convert the spec into an SDD-format plan file
   (`tasks/multi-instance-plan.md`, `## Task N:` headings, a Global
   Constraints section copied from the spec §6 + repo `CLAUDE.md`
   non-negotiables) and commit it. Suggested task cut: spec Phase 1 Tasks
   A–E as plan Tasks 1–4 (A alone; B; C; D+E together or split by judgment),
   then the Phase 2 harness as two tasks (design-approval gate, then
   implementation), then Phase 3 docs, then a final QA task that re-runs
   everything gate-style. Read `tasks/lessons.md` at session start — every
   entry there was paid for.
4. **Architect before code where a design is open.** The Phase 2 chaos
   harness needs a design doc (spec §4 lists required sections) written by a
   Fable-tier architect agent into the SDD workspace, reviewed and APPROVED
   by you (append a ruling section) before its implementation task
   dispatches. Rule on open questions yourself; only stop for decisions that
   genuinely belong to the user.
5. **Reviews are adversarial.** Reviewers get: the task brief, the
   implementer's report, a review-package diff file, and the verbatim global
   constraints. Never pre-judge findings for a reviewer. Any Critical or
   Important finding enters the fix loop (resume the same implementer,
   rounds 1–3). Implementer reports claiming test evidence must SHOW output,
   not narrate it.
6. **QA is a gate, not a fixer.** The QA task verifies with evidence
   (process identity + artifact identity per the spec §6) and reopens the
   owning task on failure — it never patches. QA found real bugs in both
   prior cycles; expect the same.

## Operating rules learned from infrastructure pain (follow these exactly)

- **Dispatch agents SYNCHRONOUSLY** (`run_in_background: false`). Background
  dispatches suffered repeated stream-watchdog stalls and connection kills;
  synchronous agents were stable. If an agent must background a long shell
  command, expect its auto-resume to be lossy: shepherd it by watching its
  artifact files and resuming it via SendMessage with a precise state
  summary (`git log`/`git status` + what remains).
- **Implementers commit incrementally** — every coherent green checkpoint,
  never >30 minutes uncommitted. This turned agent deaths from disasters
  into non-events. Put it in every implementer dispatch.
- **Agent-death protocol:** on a transient death, check the worktree
  (`git log`, `git status`), resume the same agent with a precise state
  summary. After two deaths on the same step: fresh agent over the surviving
  tree (works because of incremental commits). If fresh agents also die
  pre-code, split the task into smaller sequential dispatches; as a last
  resort do the step inline yourself (reviews can be done inline; record
  findings in a file).
- **Long reviewer outputs die on the stream.** Have reviewers of large diffs
  write findings to a file INCREMENTALLY (one small append per section) and
  return a ≤3-line verdict.
- **Never `git stash`** (the stash stack is shared across worktrees);
  temporary reverts for RED evidence via `git show HEAD:<path>` restoration.
- **Artifact identity:** every evidence log embeds `pwd` +
  `git rev-parse --show-toplevel` + branch + timestamp inside the file, in a
  per-cycle scratch subdirectory. Never trust a log by filename + recency —
  stale artifacts from a prior cycle have masqueraded as passing evidence
  before.
- **Model selection:** implementers sonnet by default, opus for the
  subtlest engine work; reviewers sonnet (opus/fable for the final
  whole-branch review); architects fable. Specify the model on every
  dispatch.
- **E2E runs:** the loan E2E supports `E2E_NO_TEARDOWN=1` — use it when a
  later gate needs the services still running. Verify which process served
  every result (PID vs the run's own pid file), and that deployed jars
  contain this branch's classes.

## Definition of done

All spec phases complete; every task review clean or parked-with-ruling;
final whole-branch review clean after at most one fix wave; full
`./gradlew build` green on the exact tree to integrate; the new e2e-tagged
suites green 3× and wired into nightly CI; Issue 11/12 evidence appended to
`docs/open-issues.md`; docs truthful (no stale claims, including removing the
"e2eTest runs zero tests" note once that stops being true); branch pushed and
PR opened against main after the user picks the integration option. Update
`tasks/todo.md` with the milestone and keep `tasks/lessons.md` current with
anything this cycle teaches.
