# Fix 2 — chaos harness: node unreachable after PARTITION → RECONNECT

Branch `worktree-green-main`. Evidence under
`.superpowers/sdd/green-main/evidence/fix-2/`; every number below is greppable
from the file named beside it.

## 1. The decisive experiment

`PublishedPortReconnectExperimentIT` (temporary; deleted in `51eb9bc` once its
verdict was archived) started a socat echo container on a test-owned subnet with
a published port, applied `docker network disconnect --force`, then
`docker network connect`, and measured three things: the container IP, what
`getMappedPort()` returns, what Docker's own NAT table advertises, and whether
the port answers.

Raw: `11-experiment-raw.log`, greps `12-experiment-greps.txt`.

**Case A — reconnect letting Docker's IPAM choose the address:**

```
[expA] BEFORE: ip=10.174.244.2 getMappedPort=59457 dockerNat=0.0.0.0:59457 :::59457 reachable=true
[expA] RECONNECTED: ipBefore=10.174.244.2 ipAfter=10.174.244.2 ipChanged=false getMappedPortBefore=59457 getMappedPortAfter=59457 portNumberChanged=false
[expA] VERDICT: publishedPortReachableAfterReconnect=true (host=localhost port=59457)
```

**Case B — reconnect on a different address (what a busy network produces once
the freed address has been taken by a `replace()`d container):**

```
[expB] BEFORE: ip=10.174.244.2 getMappedPort=59440 dockerNat=0.0.0.0:59440 :::59440 reachable=true
[expB] PARTITIONED: ip=<none> getMappedPort=59440 dockerNat=0.0.0.0:59440 :::59440 reachable=false
[expB] RECONNECTED: ipBefore=10.174.244.2 ipAfter=10.174.244.99 ipChanged=true getMappedPortBefore=59440 getMappedPortAfter=59440 portNumberChanged=false dockerNat=0.0.0.0:59440 :::59440
[expB] VERDICT: publishedPortReachableAfterReconnect=false (host=localhost port=59440)
```

**What it showed.** The brief's hypothesis is confirmed, with one refinement
worth recording: the breakage is *conditional on the address changing*, not on
the reconnect itself. When Docker hands the container back its old address the
published port works again (case A) — which is why the nightly failure is
seed-dependent and why the harness got away with this for so long. When the
address changes, the port is dead, and **`getMappedPort()` returns the same
number it returned before** (`portNumberChanged=false`) — Docker's own NAT table
still advertises `0.0.0.0:59440`. There is nothing new to re-resolve. A "simple
re-resolve the mapped port" fix is not merely inelegant, it is a no-op: it would
return the identical dead number. The ambassador (or some other non-NAT route)
is therefore required.

## 2. The fix

Kept the inherited approach, now justified by the experiment rather than assumed.

- `NodeAmbassador` — one `alpine/socat` container inside the chaos network,
  started *before* any node, publishing one host port per node role and
  forwarding each to `<alias>:8080`. It is not a `NodeRole` and never harassed,
  so its own published-port NAT is programmed once and stays valid for the run.
  `socat ... ,fork TCP:<alias>:8080` resolves the alias through Docker's embedded
  DNS **per accepted connection**, so it follows the node wherever it goes.
- `ChaosCluster.baseUrl(role)` now returns `ambassador.baseUrl(role.alias())` —
  one endpoint per role, fixed for the whole run.
- Node containers **no longer publish a port at all** (`withExposedPorts` removed).
  This is deliberate: the broken route is now impossible to use by accident,
  because `getMappedPort()` throws rather than returning a number with nothing
  behind it.

None of the forbidden shortcuts were taken: the heal timeout is unchanged
(3 min), `awaitAllNodesHealthy` still gates `healAll()`, PARTITION is still in
the action mix at weight 20.

### The ambassador must not mask a real outage

`socat`'s listener stays bound whether or not the backend exists, so a
**connect-only** probe would be fooled. `PartitionReachabilityIT` now pins this
explicitly (`aDeadNodeIsStillReportedDown`): with nothing behind the alias, and
again after `kill -9`, the round-trip probe must fail. The test also records the
hazard rather than leaving it implicit —
`[repro] bare TCP connect with the backend dead: accepted=true`
(`13-repro-AFTER-raw.log`). The harness's own probe, `ChaosCluster.isHttpUp`,
sends a real HTTP request and requires a status line `< 500`; a socat accept
followed by an immediate close surfaces as an `IOException` → `false`. So the
ambassador cannot turn a dead node green.

## 3. Before / after

**Before** — `01-repro-BEFORE-raw.log`, `02-repro-BEFORE-greps.txt` (the repro as
committed at `f0219f1`, probing the node's own published port):

```
[repro] node up: alias=verify-b ip=10.173.244.2 harness endpoint=localhost:59057
[repro] RECONNECT applied; ip now '10.173.244.99'; docker still advertises host port 59057
Chaos harness: a reconnected node is reachable again > the harness endpoint for a node still works after PARTITION + RECONNECT FAILED
org.opentest4j.AssertionFailedError ... expected: <true> but was: <false>
1 test completed, 1 failed
```

**After** — `13-repro-AFTER-raw.log`, `14-repro-AFTER-greps.txt`, three tests
green:

```
[repro] node up: alias=verify-b ip=10.173.244.3 harness endpoint=localhost:59559 direct(published)=localhost:59561
[repro] RECONNECT applied; ip now '10.173.244.99'; docker still advertises host port 59561
[repro] node's own published port localhost:59561 after RECONNECT: reachable=false
[repro] replacement up at ip=10.173.244.3; endpoint unchanged: localhost:59569
[repro] bare TCP connect with the backend dead: accepted=true
BUILD SUCCESSFUL
```

The ambassador endpoint (`59559`) survives the reconnect-on-a-different-address
that kills the node's own published port (`59561`), and is unchanged across a
container replacement.

## 4. Audit of other endpoints cached across a container lifetime

| Endpoint | Where | Verdict |
|---|---|---|
| Node HTTP base URL | `ChaosCluster.baseUrl` (was `c.getHost()`+`getMappedPort`) | **This was the bug.** Now ambassador-routed and run-stable. |
| Postgres JDBC | `ChaosCluster.dataSource(svc)` :358–361 | Safe. A *fresh* `PGSimpleDataSource` is built on every call from `postgres.getHost()`/`getMappedPort(5432)`; never held in a field. Postgres is only ever SIGSTOP/SIGCONT'd (`pauseBackend`/`unpauseBackend`, the only backend action — `ChaosController:181–189`), never disconnected, so its NAT mapping is never invalidated. All 15 call sites (`InvariantChecker:528`, `PeriodicChecker:131`, `MetricsSampler:181,204`, `WorkloadDriver:756,770,786,804`, `SideEffectCensus:174`, `ChaosRun:258`, `ChaosGoldenRunE2EIT`, `ClusterBootSmokeIT`) go through it per use. |
| Kafka bootstrap | `ChaosCluster:221,246` | Immune by construction: the test JVM never uses a host port for Kafka. Topic creation and consumer-group state run `execInContainer` against `localhost:9092`. Nodes use the alias `kafka:9092`. |
| Valkey | `ChaosCluster.valkeyCli` :418–424 | Immune: `execInContainer`, no host port. Nodes use `VALKEY_HOST=valkey`. |
| Workload driver endpoints | `WorkloadDriver.post` :711, `baseUrlOrNull` :734 | Safe: resolves `cluster.baseUrl(role)` per request, no caching. |
| Periodic checker / metrics sampler / invariant checker / side-effect census | see JDBC row | Safe: JDBC only, per-call. |
| Per-node boot URL | `ChaosCluster.replace` :553 (log line), `isHttpUp` :650 | Safe: built per use, never stored. |

Nothing else holds an endpoint across a container lifetime. The one structural
hardening added: because nodes no longer publish a port, a future caller cannot
reintroduce the broken route silently — `getMappedPort(NODE_PORT)` throws.

## 5. Final verification

_(filled in below from the full seeded run)_
