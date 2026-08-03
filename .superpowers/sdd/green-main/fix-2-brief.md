# Fix 2 brief — chaos harness: node unreachable after PARTITION → RECONNECT

## Bug

Nightly `E2E (loan-origination)` on `main` is red. Run 30731056376, job "Chaos
PR-gate (3x consecutive)": iteration 1 PASSED; iteration 2 died in `healAll()`'s
`awaitAllNodesHealthy(3 min)` at 181.9s with
`IllegalStateException("Node VERIFY_B did not become HTTP-ready")`.

VERIFY_B had been `PARTITION`ed via `docker network disconnect --force`, then
reconnected. It was alive the whole time — still processing workflows over Kafka
and Postgres until 03:59:46 — but the harness could not reach it over HTTP.

**Cause (to confirm by experiment):** Docker programs a published-port NAT rule
once at container start; `network connect` does not re-publish it, and there is
no API to re-publish on a running container. So calling `getMappedPort()` again
returns the *same, now-dead* port. That is why a naive "re-resolve the mapped
port" fix cannot work.

Seed-dependent: `KILL9`/`ROLLING_RESTART` heal via `replace()` (fresh container,
fresh port) and are immune; only PARTITION is affected. No correctness claim was
violated — the invariant checker never ran. Purely a harness bug.

Fuller write-up:
`/private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/cc624dd2-e777-4129-91f4-84b83efb4a78/scratchpad/main-red-investigation.md`

## State inherited (two agents died on transient API errors; work survived)

Committed, HEAD `f0219f1`: "test: deterministic repro for the stranded published
port after RECONNECT" → `PartitionReachabilityIT`. Its parents (`67e59d2`,
`6296427`, `82a4a65`) belong to a different, already-approved fix — leave alone.

Uncommitted in the tree:
- new `.../e2e/chaos/NodeAmbassador.java` (138 lines) — a never-partitioned
  `socat` container inside the chaos network forwarding a run-stable host port to
  each node's **network alias**, re-resolved by Docker DNS per connection.
- modified `.../e2e/chaos/ChaosCluster.java`, `.../e2e/chaos/PartitionReachabilityIT.java`

Read all three and `git diff` before changing anything.

## Tasks

1. Run the decisive experiment and archive it: after `network disconnect --force`
   + `network connect`, prove whether the original published port is dead and
   whether `getMappedPort()` still returns that dead number. If the simple
   re-resolve turns out to work, discard the ambassador and take the simple fix.
   Report what the experiment actually showed.
2. Finish the fix (ambassador or simple, per the experiment). `PartitionReachabilityIT`
   must fail before and pass after. `baseUrl()` must hold across
   partition→reconnect. Seeded runs stay deterministic. If the ambassador is
   kept, a genuinely dead node must still fail the health probe loudly — the
   ambassador must not mask it.
3. Audit other cached endpoints: `baseUrl()`, workload-driver endpoints, periodic
   checker, metrics sampler, per-node boot URLs, any JDBC/Kafka endpoint cached
   across a container lifetime. A previous cycle hit this shape with a cached JDBC
   URL. List what you checked and found.
4. Do NOT weaken the harness: no lengthened heal timeout as the fix, no skipping
   the post-partition health check, no removing PARTITION.
5. Final verification: the reproduction, plus
   `./gradlew :maestro-integration-tests:e2eTest --rerun-tasks` with a seed whose
   schedule actually contains a PARTITION (`-Dmaestro.chaos.seed=<n>`, schedule is
   seeded/deterministic). State the seed. A full run is ~10 min — keep it out of
   the edit loop.

## Rules

Java 25; no Lombok; harness in `maestro-integration-tests` under
`io.b2mash.maestro.integration.e2e.chaos`.

**Commit every ~15 minutes** — three agents have died here; incremental commits
are why nothing was lost.

**Evidence:** every number/timing/status line you quote must be greppable from a
log you archived under `.superpowers/sdd/green-main/evidence/` with an identity
header (pwd, `git rev-parse HEAD`, branch, timestamp). Run the greps before
reporting. Never quote from memory.

Report to `.superpowers/sdd/green-main/fix-2-report.md`.
