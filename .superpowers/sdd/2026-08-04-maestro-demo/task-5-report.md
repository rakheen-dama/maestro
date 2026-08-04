# Task 5 — report: the self-contained presentation

**Artefact:** `demo/presentation/index.html` — one file, 20 slides, inline CSS
and JS, no build step. Open it by double-clicking; edit it with any editor.

**Offline evidence:** `demo/.evidence/task-5-presentation-offline-verification.log`

Built across four dispatches (shell → Act I → Act II → Act III + code slides)
with a commit after each group, because eleven agents died mid-response on this
project and small-and-committed beats complete-and-lost.

## Slide inventory

Each row's `DO:` block names the runbook section a presenter should be driving
while that slide is up. Slides marked *(none)* are narration only and say so.

| # | slug | title | act | `DO:` references |
|---|---|---|---|---|
| 1 | `title` | Maestro | I | §0 pre-flight (already done); `docker compose ps` if proof wanted |
| 2 | `problem` | The problem, in business terms | I | *(none — framing before §1)* |
| 3 | `flow` | The flow — eight steps | I | *(none — the spine of §1)* |
| 4 | `pressure` | What the domain forces | I | *(none — maps to §2, §4, §D1, §D6)* |
| 5 | `architecture` | Architecture — there is no server | I | **§D4** |
| 6 | `scenario-1` | Scenario 1 — one loan, three services | II | **§1** |
| 7 | `scenario-2` | Scenario 2 — kill -9 | II | **§2** |
| 8 | `the-pause` | Scenario 2 — narrating the pause | II | **§2**, during `drive-loan.sh finish` |
| 9 | `scenario-3` | Scenario 3 — the trace | II | **§3** |
| 10 | `scenario-4` | Scenario 4 — saga compensation | II | **§4** |
| 11 | `scenario-5` | Scenario 5 — parked costs nothing | II | **§5** |
| 12 | `d1` | D1 — the rolling deploy | III | **§D1** |
| 13 | `d1-traces` | D1 — the two trace shapes | III | **§D1** PIN 6 |
| 14 | `d1-code` | D1 — the code that makes it safe | III | **§D1** (code behind the assertions) |
| 15 | `d2` | D2 — how memoization works | III | **§D2** |
| 16 | `d3` | D3 — the evidence | III | **§D3** (never run live) |
| 17 | `d4` | D4 — what you actually operate | III | **§D4** |
| 18 | `d5` | D5 — determinism | III | **§D5** |
| 19 | `d6` | D6 — multi-node adoption | III | **§D6** |
| 20 | `authoring` | What you actually write | III | *(none — closing slide)* |

Every one of the 20 slides carries both a `SAY:` and a `DO:` block; verified
programmatically, not by eye.

## Keys

`→` `space` `PageDown` next · `←` `PageUp` `Backspace` previous · `Home`/`End`
· `g` jump-to-slide (filters on number, title, slug or act — type `d4`, `scenario 2`)
· `p` presenter panel · `P` presenter in a second window · `f` fullscreen ·
`n` inline notes · `Esc` close.

Deep links work directly: `index.html#d4` opens D4. Deep dives are reachable
without walking the deck, which is the point — a presenter drops into one on
demand and leaves without stranding the narrative.

## Offline verification

Run from a `file://` URL in **real Chrome with no server running**, every
non-deck request aborted at the browser level. Verdict **PASS**:

- 20 slides discovered; `ArrowRight` walk through all 20 lands on the right
  slide every time; `Home`/`End` correct.
- **20/20 deep-link slugs** resolve after a full page reload on `file://`.
- Presenter panel: renders, 295 chars of notes on slide 1, **1 next-slide
  preview** rendered live, clock running.
- Jump overlay: typing `d4` filters to one hit; `Enter` lands on `#d4`.
- **Subresources fetched by the document: 0.** No stylesheet, script, font or
  image is loaded from anywhere. This is the number that matters in a
  conference room.
- Page errors: none.

**Overflow, measured with presenter view ON — the mode that matters.** The
whole deck is walked with its own `ArrowRight` navigation in both modes at both
resolutions and each active slide measured:

| | 1440×900 | 1280×720 |
|---|---|---|
| presenter view ON | 0/20 clipped (3 auto-fit, 0.74–0.93) | 0/20 clipped (10 auto-fit, 0.62–0.95) |
| presenter view OFF | 0/20 clipped (1 auto-fit, 0.99) | 0/20 clipped (3 auto-fit, 0.83–0.97) |

The first round of this report claimed "zero vertical overflow on all 20
slides" — but measured it with presenter view **off**, i.e. not while
presenting. With it on, 8/20 clipped at 1440×900 and 14/20 at 1280×720. See
*Fix round 1* below.

## What a reviewer should check

1. **The `version()` bound on slide 14.** It must read
   `workflow.version(PARALLEL_VERIFICATION, WorkflowContext.DEFAULT_VERSION,
   PARALLEL_VERIFICATION_VERSION)`. The obvious-looking `(changeId, 1, 2)`
   throws `UnsupportedWorkflowVersionException` and fails every in-flight loan.
   The slide also carries a card explaining why, so an audience member reading
   ahead cannot take the wrong lesson.
2. **The D1 wording.** The v2 change is stated on the slide as *"documents now
   collect while the verifications are still outstanding"* — two branches. The
   phrase "three-way verification fan-out" appears nowhere in the file. Neither
   does any claim that the three-way *send* fan-out demonstrates v2; slide 9's
   notes explicitly forbid pointing at it, since it is identical in v1.
3. **Code provenance.** Both code slides name their source file on the slide
   itself and state what was elided:
   - slide 14 — `maestro-samples/sample-loan-origination/loan-application-service/src/v2/java/…/workflow/LoanApplicationWorkflow.java`
     (comments elided, lambda bodies folded onto one line)
   - slide 20 — `maestro-samples/sample-postgres-only/src/main/java/…/workflow/DocumentApprovalWorkflow.java`
     (91 lines in full; the caption now names every elision: comments,
     `assignReviewer`, the `currentStep` field and its `@QueryMethod
     getStatus()`, three `archiveAuditTrail` calls, the rejection branch)
   Neither was written from memory. Slide 20 deliberately drops the sample's
   unused `reviewerId` local so nobody in the room notices a dead variable.
4. **Figures are attributed, and the un-promisable ones are flagged.** Slide 11
   shows the parked peak of 12 but its notes say *promise the shape, not the
   number* (an earlier rehearsal peaked at 9). Slide 13's notes say PIN 6's
   span counts are pre-flush undercounts and must not be read out. Slide 7's
   notes say to read the parked row count off the screen rather than promise
   one.
5. **The honesty cards are load-bearing, not decoration.** The residual ~33 s
   in scenario 2 is attributed to Kafka's consumer session timeout, not
   Maestro; "how fast does it recover" is answered with the knob (5 s here,
   60 s shipped default, 250 s measured at that default); D4 answers
   exactly-once with "no — at-least-once execution with exactly-once persisted
   results". If any of these get trimmed for time, the deck starts overclaiming.
6. **One defensive change worth knowing about.** The deck's slide query is
   scoped to `#stage > .slide`. The presenter's next-slide preview holds a
   *cloned* `.slide`, so an unscoped `document.querySelectorAll('.slide')`
   returns 21 elements once the preview has rendered. The deck captured its
   list once at startup and was never actually wrong, but the scoping means a
   future edit that re-queries cannot inherit the bug. The offline check caught
   this.

## Known limits

- The presenter's second-window mode (`P`) uses `window.open` +
  `document.write`. It degrades to nothing if a popup blocker intervenes; the
  in-page panel (`p`) is the primary and was the one verified offline.
- The trace comparison on slide 13 is a **CSS schematic**, labelled as one on
  the slide ("schematic — the shape, not the timings"). It is not a screenshot
  of Jaeger. The real trace ids are printed beneath each panel so the presenter
  can open the genuine article.
- The event-log tables on slides 6 and 15 both carry the real v1 activity names
  from the runbook's own D2 walk-through, with the middle rows elided and said
  to be elided. Slide 6's earlier "shape only" table invented three of them.

## Fix round 1

Ten findings from `task-5-fixes.md`, one commit each.

| | finding | fix |
|---|---|---|
| C1 | slide 6's event log showed `validateApplication`, a singular `requestVerification` and a `SIDE_EFFECT $maestro:currentTime` row — none of which exist — while its `DO:` sends the presenter to the live log in the same breath | table replaced with the real v1 rows (matches slide 15 and `RUNBOOK.md:592-598`) |
| C2 | presenter view clipped 8/20 slides at 1440×900 and 14/20 at 1280×720; 2/20 clipped even with it off at 1280×720 | each slide's content wrapped in a `.fit` element the deck scales down only when it would not fit; presenter panel 33vh→30vh and the slide reclaims top padding so the scale stays mild. `k` stays 1 for anything that already fits |
| C3 | D5 told the presenter to point at `SIDE_EFFECT` rows in D2's log; the loan workflows call neither `currentTime()` nor `randomUUID()`, so none exist | both the slide and `RUNBOOK.md` now point at `FundingActivities.reserveRateLock`, which mints a random id *inside an activity* — same lesson, real row |
| I4 | D6's `TWO_NODE=1 … start-services.sh` failed as typed (ports still bound) and its `grep -l` had no file arguments, so it hung on stdin | `stop-services.sh` first; grep names both node logs |
| I5 | slides 7, 12, 19 — the three riskiest live runs — carried no `FALLBACK:` | added from `RUNBOOK.md` §2/§D1/§D6; D1's brings in `task-3-live-v1-to-v2-move.log`, previously unreferenced by the deck |
| I6 | `drive-loan.sh` named without its `demo/scripts/` prefix in two commands | prefixed |
| M1 | slide 20's elision caption was incomplete and `SAY:` claimed "not a cut-down illustration" | caption names every elision; `SAY:` says what came out and why it changes nothing |
| M2 | `pre.code .a` painted annotations rose, the hue reserved for failure | brass |
| M3 | 2.27 GiB uncited on-slide and absent from the runbook | cited on slide 17 and in its notes; `RUNBOOK.md` §D4 gains the breakdown |
| M4 | click-to-advance fired on the click ending a text selection | a click advances only when the pointer did not travel and nothing is selected |

Re-verified offline afterwards **with presenter view on** — see the table above
and `demo/.evidence/task-5-presentation-offline-verification.log`.
