# WP10 Wake-Listener Shutdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Closing `PostgresWakeListener` on a pooled `DataSource` logs no pool `WARN`, and its connection goes back to the pool intact, listening on nothing (Issue 38). Only the poll thread ever touches the `LISTEN` connection. `close()` stops the thread without an interrupt, waits a bounded 2 s for it, and never takes the connection from a thread that may still be using it.

**Architecture:** The poll thread owns the connection from `start()` to the end. `close()` does three things: it clears `running`, counts a stop latch down (which wakes a back-off wait without an interrupt), and joins the thread for at most 2 s. The thread leaves its loop within one `getNotifications(500 ms)`. In a `finally` it puts a 1 s network timeout on the connection, issues `UNLISTEN *` and closes the connection, which returns it to the pool. A session that already failed is closed without `UNLISTEN`. If the join times out, `close()` logs one `INFO` line and leaves the connection to the thread, which releases it when its current call returns. The bean stays a `@Bean(destroyMethod = "close")`. It is destroyed after every lifecycle phase (so after the engine has drained) and before the `DataSource`.

**Tech Stack:** Java 25, `maestro-store-postgres` (pgjdbc 42.7.8, HikariCP 7.0.2 on the test classpath through `spring-boot-starter-jdbc`, logback-classic 1.5.32, Testcontainers `postgres:16-alpine`), Spring Framework 7.0.6 / Spring Boot 4.0.5 for the context test, JUnit 5, Awaitility.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md`: the WP10 row ("Reproduce the WARN first; it was traced, not seen"), Lane B order, §2 anti-goals, §6. Evidence: `evidence/audit-C-host-integration-followups.md` § Issue 38. Issue: `docs/open-issues.md` Issue 38 (`:3404-3428`). Siblings:
- `wp04-loop-liveness-health.md`: its Task 5 builds on this plan's loop shape, `awaitStop` and `ScriptedPgDataSource`.
- `wp02-wake-timers-activity-lock.md`: house style.

All line numbers below are for `main` @ `525b287`. Re-check them after cutting the branch.

## Added, changed and removed public surface

**Added:** no public type, property, SPI method, meter or migration.

**Package-private** (test seams): `PostgresWakeListener(DataSource dataSource, String channel, Duration closeJoin)`. The public two-argument constructor delegates with `CLOSE_JOIN = Duration.ofSeconds(2)`.

**Changed behaviour**

| Surface | Before | After |
|---|---|---|
| `PostgresWakeListener.close()` | Interrupts the poll thread (`:153`), which closes the socket under a connected `getNotifications`. It then runs `UNLISTEN *` on the caller's thread on that closed connection, and closes it. With Hikari this logs `WARN com.zaxxer.hikari.pool.ProxyConnection … marked as broken because of SQLSTATE(08003)` with a stack trace, and the pool evicts the connection. | No interrupt. It clears `running`, counts the stop latch down, and joins for ≤ 2 s. The poll thread runs `setNetworkTimeout(1 s)`, `UNLISTEN *` and `close()` itself. No pool `WARN`; the pool keeps and reuses the same physical connection, listening on nothing. |
| Join timed out | Runs `UNLISTEN *` and `close()` anyway, on a connection the thread may still be using | Logs one `INFO`, does not touch the connection. The thread releases it when its current call returns. |
| A session that failed while stopping | Swallowed; `close()` then tried `UNLISTEN *` on it | Closed without `UNLISTEN`, on the poll thread |
| Back-off wait between reconnects | `Thread.sleep(backoff)`, ended by the interrupt | `stop.await(backoff)`, ended by `close()` counting the latch down |
| Close duration | ≈ 0 ms (the interrupt) | ≤ 500 ms typically (one poll); ≤ 2 s bound. Measured 0–408 ms at planning time (Verified 4) |
| Javadoc "Thread Safety" | "touched only by that thread (and by `close()`, which first stops it)" | "touched only by the poll thread once `start()` returns" |

**Removed:** the `thread.interrupt()` in `close()`.

## Verified at planning time (and how)

Read from `main` @ `525b287`, from the pgjdbc 42.7.8, HikariCP 7.0.2 and spring-beans 7.0.6 jars in `~/.gradle/caches/modules-2` (`javap`), and by **running two standalone probes** (2026-11-17). Neither probe is committed. They compiled `main`'s `PostgresWakeListener` and `WakeChannel` unchanged, against a throwaway `postgres:16-alpine` container and a real `HikariDataSource` (pool size 1–2, logback at `DEBUG` for `io.b2mash`).

1. **The WARN is real, and Hikari emits it, not Maestro.** Five rounds of start → connected → wait 700 ms → `close()` gave five of these:

   ```
   WARN  [main] com.zaxxer.hikari.pool.ProxyConnection - probe - Connection org.postgresql.jdbc.PgConnection@51f116b8 marked as broken because of SQLSTATE(08003), ErrorCode(0)
   org.postgresql.util.PSQLException: This connection has been closed.
   DEBUG [main] io.b2mash.maestro.store.postgres.notify.PostgresWakeListener - UNLISTEN * on close failed (ignored): This connection has been closed.
   INFO  [main] io.b2mash.maestro.store.postgres.notify.PostgresWakeListener - Postgres wake listener on channel 'maestro_signal_wake_probe_0_ba6bc811' closed
   ```

   A sixth round in the second probe gave the same lines: **6 of 6**. The logger is `com.zaxxer.hikari.pool.ProxyConnection`, which settles Issue 38's "confirm which logger emits it". The SQLSTATE is `08003` (connection does not exist), not the `08006` Audit C inferred: the interrupt had already closed the connection, so the `UNLISTEN` failed `checkClosed`.
2. **Where the poll thread is when `close()` interrupts it.** It is `TIMED_WAITING`, in a socket read:

   ```
   at org.postgresql.core.VisibleBufferedInputStream.readMore(VisibleBufferedInputStream.java:192)
   at org.postgresql.core.PGStream.receiveChar(PGStream.java:477)
   at org.postgresql.core.v3.QueryExecutorImpl.processNotifies(QueryExecutorImpl.java:821)
   at org.postgresql.jdbc.PgConnection.getNotifications(PgConnection.java:1234)
   at io.b2mash.maestro.store.postgres.notify.PostgresWakeListener.pollLoop(PostgresWakeListener.java:197)
   ```

   An interrupt of a virtual thread blocked in a socket read closes the socket (JDK semantics for virtual threads). The probe shows the consequence: the connection is closed by the time `close()` reaches `UNLISTEN`. The `getNotifications` that the interrupt broke throws an I/O `PSQLException`. The loop's `catch (SQLException)` (`:203-208`) swallows it because `running` is `false`, and the loop exits.
3. **Today the connection is evicted, not leaked.** After each `close()` a borrowed connection had `pg_listening_channels()` = 0, and the pool held a different physical connection (`total=1 idle=1` after the evicted one was replaced).
4. **The proposed order works.** The second probe emulated it by reflection: clear `running` without an interrupt, `join(2 s)`, `UNLISTEN *` on the still-open connection, close. Five rounds gave:
   - joins of 197, 101, 0, 408 and 318 ms;
   - no `WARN`;
   - a pool of size 1 that kept `total=1`, so the same physical connection was reused;
   - `pg_listening_channels()` = 0 on the next borrow.
5. **A `LISTEN` left on a returned connection leaks into the host's pool.** Returning the connection without `UNLISTEN` made the next borrower's `pg_listening_channels()` return **1**. Hikari does not reset session state such as `LISTEN`. So a join timeout must never return a connection that is still listening; that is why the poll thread, not `close()`, releases it.
6. **pgjdbc details.**
   - `QueryExecutorImpl.processNotifies(int)` reads the socket's `SO_TIMEOUT` and restores it after each call. Once `getNotifications(500)` returns, an `UNLISTEN` has no read timeout unless one is set; on a half-open TCP connection it would block until the kernel gives up. Hence `setNetworkTimeout(…, 1000)` before it.
   - `PgConnection.setNetworkTimeout` ignores its `Executor` argument.
   - Hikari's proxy records the change and restores the pool's value when the connection is returned.
7. **Bean destruction order** (traced; Task 3 pins it).
   - `GracefulShutdownHandler` (a `SmartLifecycle` in phase `Integer.MAX_VALUE - 1024`, `GracefulShutdownHandler.java:63`) drains the executor during the lifecycle stop, before any bean is destroyed.
   - `destroySingletons` then destroys the beans that depend on a bean before that bean. `WorkflowExecutor` takes `@Nullable SignalNotifier` directly (`MaestroAutoConfiguration.java:95`). `PostgresSignalNotifier` takes the listener directly (`PostgresSignalWakeAutoConfiguration.java:113-115`). So the executor and the notifier are destroyed before the listener.
   - The listener gets its `DataSource` through `ObjectProvider`s (`:90-92`). `DependencyObjectProvider` calls `doResolveDependency(descriptor, beanName, null, null)` (spring-beans 7.0.6, `javap`), which registers **no** dependent-bean link. The `DataSource` is nevertheless registered for disposal before the listener, because it is created before or during the listener's factory method. Disposal runs in reverse registration order, so the listener is destroyed first and the pool is still open when it releases its connection.
8. **No existing test depends on the interrupt.** `PostgresNotifyTestSupport.tearDownNotify` (`:55-59`) calls `close()` on a `PGSimpleDataSource` listener. After this change each such close waits up to one poll (≤ 500 ms). About 15 tests extend the support class (`PostgresSignalNotifierTest` has 13, `PostgresWakeListenerReconnectTest` 2), so the module's suite grows by ≲ 8 s.

## Today's shutdown sequence (`main` @ `525b287`)

1. Context close: the lifecycle stop runs. `GracefulShutdownHandler.stop` → `WorkflowExecutor.shutdown()` stops the loops, abandons parks, drains for ≤ `maestro.shutdown.timeout`, then closes the instance-lock renewer and the lifecycle publisher (`WorkflowExecutor.java:1668-1733`). Kafka containers stop in their own phases.
2. `destroySingletons`: the executor (inferred `shutdown()`, idempotent) and `postgresSignalNotifier` (`close()` only logs, `PostgresSignalNotifier.java:162-165`) go first, then `maestroPostgresWakeListener.close()`:
   1. `running` goes from `true` to `false` (`PostgresWakeListener.java:148`);
   2. `thread.interrupt()` (`:153`). The poll thread is in `getNotifications(500)`, and the socket is closed (Verified 2);
   3. the poll thread's `getNotifications` throws an I/O `PSQLException`. `catch (SQLException)` sees `running == false`, logs nothing and does not close; `while (running)` ends the loop;
   4. `join(2000)` (`:155`) returns;
   5. `UNLISTEN *` on the pool proxy fails with `08003`. Hikari's `ProxyConnection.checkException` logs the `WARN` with a stack trace and marks the connection evicted. Maestro logs `DEBUG` (`:160-166`);
   6. `closeConnection()` (`:289-300`): the proxy's `close()` hands back an evicted connection, and Hikari closes it;
   7. `consumers.clear()`, then `INFO … closed`.
3. The `DataSource` (Hikari) is destroyed: `Shutdown initiated...`, `Shutdown completed.`

## Decisions

### D-1. The new sequence

**`close()`** (any thread):
1. `if (!running.compareAndSet(true, false)) return;`
2. `stop.countDown()`, where `stop` is a `CountDownLatch(1)` field. This ends a back-off wait at once.
3. **No interrupt.** `thread.join(closeJoin)`. If the joining thread is interrupted, restore its flag and go on as if the join had timed out.
4. If the poll thread has ended, it has already released the connection (D-2). Log `INFO Postgres wake listener on channel '{}' closed`, as today.
5. If it has not ended, log `INFO Postgres wake listener on channel '{}' did not stop within {} ms (its poll thread is still in a database call); it releases its connection when that call returns`. Do not touch `connection`.
6. `consumers.clear()`.

**`pollLoop()`** (the poll thread):

```
try {
    while (running) { … today's body, with these changes:
        - the back-off `Thread.sleep(backoff)` becomes `if (awaitStop(backoff)) break;`
        - `catch (SQLException e)`: if running → today's WARN; in both cases → closeConnection()   (a failed session: no UNLISTEN)
    }
} finally {
    releaseConnection();
}
```

- `private boolean awaitStop(long millis) throws InterruptedException` returns `stop.await(millis, MILLISECONDS)`. This is the seam WP4 Task 5 uses.
- `private synchronized void releaseConnection()`:
  1. Take `connection` and null `connection` and `pgConnection`. If it was `null`, return.
  2. `conn.setNetworkTimeout(Runnable::run, UNLISTEN_TIMEOUT_MS)`, in its own `try`; a `SQLException` or `RuntimeException` here (a pool or driver without the feature) is logged at `DEBUG` and ignored.
  3. `UNLISTEN *` through `conn.createStatement()`; a failure is logged at `DEBUG`: `UNLISTEN * on close failed (ignored): {}`.
  4. `conn.close()`; a failure is logged at `DEBUG`.
- `connect()` is unchanged. It already hands a connection straight back when `running` turned `false` while it was borrowing (`:237-240`).

**Constants:**
- `POLL_TIMEOUT_MS = 500` (unchanged);
- `UNLISTEN_TIMEOUT_MS = 1_000` (new);
- `CLOSE_JOIN = Duration.ofSeconds(2)` (replaces `CLOSE_JOIN_MS`).

The bound is `CLOSE_JOIN ≥ POLL_TIMEOUT_MS + UNLISTEN_TIMEOUT_MS + slack` (0.5 + 1 + 0.5 s), written as a comment on `CLOSE_JOIN`.

**Why the poll thread releases the connection, not `close()`.** This is the only ordering in which no thread ever touches a connection another thread is using, whatever the join does. It removes the race that Audit C's variant keeps: "interrupt only when `pgConnection == null`", read by `close()`, while `connect()` may set it a moment later. It also keeps a half-open `UNLISTEN` off the thread that runs context shutdown.

### D-2. Every case

| Case | What happens | Pool state afterwards |
|---|---|---|
| Connected, polling (the normal shutdown) | The in-flight poll returns within 500 ms. The loop exits; `UNLISTEN *`, then `close()`. The join returns. | The same physical connection, idle, listening on nothing (Task 1 pins it on Hikari) |
| In a back-off wait (the database was down earlier) | `stop.countDown()` ends the wait; `break`. The `finally` finds no connection. | Nothing borrowed |
| Inside `connect()`, waiting in `dataSource.getConnection()` (pool exhausted, or the database is down at shutdown; Hikari waits up to `connectionTimeout`, 30 s) | The join times out after 2 s and `close()` logs the `INFO`. When `getConnection` returns, `connect()` sees `running == false` and closes the connection before any `LISTEN`. If it throws, the loop ends. | The connection is returned unused (or never borrowed). The thread ends within `connectionTimeout`. |
| The database dies during the last poll (RST or FIN) | `getNotifications` throws. The `SQLException` arm closes the connection without `UNLISTEN`, and the loop ends. | Hikari sees a closed physical connection and drops it. Any `WARN` from Hikari is about a connection that really is broken. |
| Half-open TCP at shutdown (no RST) | The poll returns empty after 500 ms. `UNLISTEN *` times out after 1 s (network timeout) with `08006`, then `close()`. Join ≤ ~1.5 s. | Hikari marks the connection broken and logs a `WARN`, a true one. Traced only: no test here can make a half-open socket. Task 2 pins that the timeout is set before `UNLISTEN`. |
| A poll stuck past 2 s (not observed; a driver bug) | The join times out. `close()` logs and leaves. The thread releases the connection when the call returns. | Clean once the call returns. Until then the connection stays borrowed, as it would have under a stuck interrupt today. |
| `maestro.signal.wake-notify.enabled=false` (PgBouncer transaction pooling) | `PostgresSignalWakeAutoConfiguration` does not activate (`:71-72`). There is no listener and no `LISTEN` connection. | Nothing to do |
| PgBouncer in session mode | As "connected, polling". The server connection goes back clean; PgBouncer's `server_reset_query` would also clear a `LISTEN` (traced). | Clean |
| `close()` before `start()`, or twice | The compare-and-set fails, and it returns. | — |

### D-3. `maestro.shutdown.timeout`, Spring phases and context restart

- **No relation to `maestro.shutdown.timeout`.** The listener closes during bean destruction, after the lifecycle stop in which the executor drains (Verified 7). So the engine's drain is over before the listener's ≤ 2 s begins. Spring's `timeout-per-shutdown-phase` bounds lifecycle phases, not bean destruction, so it does not bound this either. The two add up. **Hand-off to WP5:** the grace arithmetic it documents gains "+ up to 2 s (typically ≤ 0.5 s) for the Postgres wake listener, during bean destruction".
- **The listener stays a `destroyMethod` bean, not a `SmartLifecycle`.** Bean destruction already gives the right window: every Maestro lifecycle stop is over (the executor drained, parked waiters abandoned, Kafka containers stopped), and the `DataSource` is still open. A `SmartLifecycle` would need a phase below the executor's `MAX - 1024`, and it would gain nothing.
- **Context restart.**
  - A close-and-recreate (DevTools, a test context, a redeploy in the same JVM) goes through the destroy path above. The new context makes a new listener with its own connection.
  - `ConfigurableApplicationContext.stop()` then `start()` without a close leaves the listener listening. It is not a `Lifecycle`, before or after this change. The engine itself does not support stop/start either, because `WorkflowExecutor.shutdown()` is one-way (`shuttingDown`, `:1669`). That is unchanged and out of scope.

### D-4. What this package does not change

- The reconnect path while `running` (the `WARN` on a failed session, the 1 s → 30 s back-off, `reconnects()`) is unchanged, except that the wait is the latch. A failed session is closed without `UNLISTEN`, as today: an `UNLISTEN` on a dead connection would only add a second failure (Verified 1's mechanism).
- The `RuntimeException` arm (`:212-219`), which can spin with no wait, and an `Error` that ends the poll thread, are WP4's (Task 5; WP4 D-9). This package's `finally` already means a thread that dies of an `Error` releases its connection and stops claiming `isConnected()`.
- `PostgresSignalNotifier` is unchanged.

### D-5. Anti-goals and mixed fleets

There is no history, wire or SPI change. A `NOTIFY` lost while a node shuts down was already covered by the peers' `wake-recheck-interval` re-read. The listener now stops listening up to 500 ms later than before, so the window is shorter, not longer. Mixed fleets are unaffected: the change is local to one node's shutdown.

## Global Constraints

- **Spec anti-goals (§2):** D-5. **An existing test is changed only as a named step. None is named:** no existing test assertion changes. An implementer who meets a failing existing test stops and asks.
- **Code rules.** Javadoc on every public member, with the thread-safety note updated as in the surface table. JSpecify `@Nullable`. No Lombok. No Spring in `maestro-core` (this package does not touch core).
- **Control-flow `Error`s.** This package adds no `catch` of `Throwable` or `Error`; WP4 owns that. `releaseConnection` catches `SQLException | RuntimeException` only.
- **No log line** carries a workflow id. The new `INFO` names the channel only, as the existing lines do.
- **Branch and worktree.** Branch `fix/wake-listener-shutdown`, cut from `origin/main` (WP7 has merged at `525b287`), in `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp10-wake-listener-shutdown`. Before the first edit, run `git -C <worktree> status` and `git -C /Users/rakheendama/Projects/2026/maestro status`. Never `cd` into the main checkout. This plan lives on `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- **Gradle.** Always `./gradlew --no-watch-fs`, from the worktree root. Never two Gradle runs on one module at once. No `git stash`.
- **Commits.** Commit at every checkpoint named below. Chain git steps with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`. Never run git-mutating commands in parallel. Every message ends with the attribution line your own session supplies.
- **Red tests** fail on the unfixed code on a positive fact (a `WARN` line, a backend pid, an event list), never on a `ConditionTimeoutException`. Paste the FAIL lines into the commit body. Each fix gets at least one mutation check that a reviewer can repeat, and the mutation is reverted in the same step.
- **No sleeps** where a latch or an awaited condition works. The only time bounds asserted are the close-duration bounds in Task 2, and they are upper bounds on a call, not waits.
- **Documentation** states only what a test shows, or it is labelled "(traced)".
- **New issues:** none planned. If one is needed, take the next free number at implementation time; 59–64 and 65–66 are reserved by parallel plans.
- **PR gates (spec §6):** Build & Test; nightly E2E on the branch; chaos PR-gate ×3. No soak for WP10. The maintainer merges.

## Review Focus

1. **A normal shutdown on Boot's default pool.** No `WARN`, and the pool gets its connection back unbroken and not listening. → Task 1 `closingTheListenerLogsNoWarnFromThePool`, `theListenersConnectionGoesBackToThePoolAndIsReused`, `theConnectionReturnedToThePoolListensOnNothing`; Task 3 `closingTheContextLogsNoPoolWarnAndClosesTheListenerBeforeThePool`.
2. **A poll thread that does not stop in time.** `close()` must return within its bound, and nobody may `UNLISTEN` or close a connection the thread is still using. The thread must release it later. → Task 2 `aPollThreadStuckPastTheJoinKeepsItsConnectionUntilItReturns`.
3. **A database that is down at shutdown.** Shutdown must not hang on it, and Maestro must not log a `WARN` of its own. → Task 2 `aCloseDuringABackOffReturnsAtOnce`, `aSessionThatFailsWhileStoppingIsClosedWithoutUnlisten`, `theNetworkTimeoutIsSetBeforeUnlisten` (the half-open case, traced in D-2).
4. **The connection is touched by exactly one thread** once the listener is started. → Task 2 `theConnectionIsTouchedOnlyByThePollThreadAfterStart`.

## Before you start

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin && \
git -C /Users/rakheendama/Projects/2026/maestro worktree add -b fix/wake-listener-shutdown \
    /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp10-wake-listener-shutdown origin/main && \
git -C /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp10-wake-listener-shutdown status && \
git -C /Users/rakheendama/Projects/2026/maestro status
```

Run every command from the worktree; all paths below are relative to it. Never `cd` into the main checkout.

## File structure

| File | Responsibility |
|---|---|
| `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/notify/PostgresWakeListener.java` | D-1 |
| `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/notify/PostgresWakeListenerShutdownTest.java` (new) | Task 1: the reproduction and its fix on real Postgres and Hikari |
| `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/notify/ScriptedPgDataSource.java` (new) | Task 2: a scripted `DataSource`, `Connection` and `PGConnection`, with no database |
| `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/notify/PostgresWakeListenerCloseOrderTest.java` (new) | Task 2: the ordering, by construction |
| `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/config/PostgresSignalWakeAutoConfigurationTest.java` (new method) | Task 3: the Spring destroy order |
| `docs/configuration.md`, `docs/open-issues.md`, `docs/release-notes.md`, `CLAUDE.md` | Task 4 |

## Pre-flight: task pairs that share a file or an interface

| Pair | Shared | Order and rule |
|---|---|---|
| 1 → 2 | `PostgresWakeListenerShutdownTest.java`; Task 2's fix turns Task 1's reds green | Task 1 commits its reds `@Disabled` with a reason; Task 2 removes the `@Disabled` in the same commit as the fix |
| 2 → 3 | The fixed `close()` | Task 3 after Task 2; a red there means Verified 7 is wrong, so stop and ask |
| 2 ↔ WP4 Task 5 | `ScriptedPgDataSource`'s API, `awaitStop`, `pollLoop`'s shape | Keep the names in Task 2's Interfaces exactly; WP4 consumes them |

---

### Task 1: Reproduce Issue 38 on real Postgres with a Hikari pool

**Files:**
- Create: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/notify/PostgresWakeListenerShutdownTest.java`. It extends `PostgresNotifyTestSupport` for the shared container. The inherited per-test listener on `PGSimpleDataSource` is unused and is closed by the support class.

**Interfaces:**
- Fixtures (this class):
  - `HikariDataSource pool`: `jdbcUrl`, user and password from `postgres`, `maximumPoolSize(1)`, `poolName("wake-shutdown-test")`. It is closed in `@AfterEach`.
  - `ListAppender<ILoggingEvent> hikariLog`, attached in `@BeforeEach` to `(ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.zaxxer.hikari")` and detached in `@AfterEach`.
  - `List<String> poolWarnings()` returns the formatted messages of events at `WARN` or above.
  - `int listenBackendPid(String channel)`: `PostgresWakeListenerReconnectTest`'s private `listenBackendPid()` (`:73-83`), parameterized by channel, through `newDataSource()`. It returns -1 when no backend runs `LISTEN <channel>`.
  - `int borrowedBackendPid()` and `int borrowedListeningChannels()`: borrow from `pool`, then `SELECT pg_backend_pid()` / `SELECT count(*) FROM pg_listening_channels()`.

- [ ] **Step 1: Check the classpath.** Run `./gradlew --no-watch-fs -q :maestro-store-postgres:dependencies --configuration testRuntimeClasspath | grep -E 'HikariCP|logback-classic'`. Expected: `com.zaxxer:HikariCP:7.0.2` and `ch.qos.logback:logback-classic:1.5.32` (both found at planning time). Add no dependency.
- [ ] **Step 2: Write the tests.**

```java
@Test
@DisplayName("closing a connected listener on a Hikari pool logs no WARN from the pool (Issue 38)")
void closingTheListenerLogsNoWarnFromThePool() {
    var listener = new PostgresWakeListener(pool, WakeChannel.channelFor("shutdown-" + unique()));
    listener.start();
    await().atMost(Duration.ofSeconds(10)).until(listener::isConnected);
    await().atMost(Duration.ofSeconds(10)).until(() -> listenBackendPid(listener.channel()) > 0);
    listener.close();
    assertEquals(List.of(), poolWarnings());
}

@Test
@DisplayName("the listener's connection goes back to the pool and is reused, not evicted")
void theListenersConnectionGoesBackToThePoolAndIsReused() throws Exception {
    var listener = new PostgresWakeListener(pool, WakeChannel.channelFor("reuse-" + unique()));
    listener.start();
    await().atMost(Duration.ofSeconds(10)).until(() -> listenBackendPid(listener.channel()) > 0);
    var pid = listenBackendPid(listener.channel());
    listener.close();
    assertEquals(pid, borrowedBackendPid(), "the pool (size 1) must hand out the same backend");
}

@Test
@DisplayName("the connection returned to the pool listens on nothing")
void theConnectionReturnedToThePoolListensOnNothing() throws Exception {
    var listener = new PostgresWakeListener(pool, WakeChannel.channelFor("clean-" + unique()));
    listener.start();
    await().atMost(Duration.ofSeconds(10)).until(() -> listenBackendPid(listener.channel()) > 0);
    listener.close();
    assertEquals(0, borrowedListeningChannels());
}
```

- [ ] **Step 3: Run red, five times.** Run `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*PostgresWakeListenerShutdownTest' --rerun` five times in a row. Expected FAILs in every run:
  - `closingTheListenerLogsNoWarnFromThePool`: `expected: <[]> but was: <[wake-shutdown-test - Connection org.postgresql.jdbc.PgConnection@… marked as broken because of SQLSTATE(08003), ErrorCode(0)]>`;
  - `theListenersConnectionGoesBackToThePoolAndIsReused`: `expected: <P> but was: <Q>`, two different pids.

  `theConnectionReturnedToThePoolListensOnNothing` passes today: the pool evicted the broken connection (Verified 3). It is a guard for the fix; its teeth are the mutation in Task 2 Step 7.

  **If the `WARN` appears in fewer than 5 of 5 runs,** record the count. A miss means the interrupt landed between two polls, which is microseconds out of every 500 ms. Then go on: Task 2's ordering tests are deterministic and carry the fix by construction. **If it appears in 0 of 5,** stop and report: the planning probe saw it 6 of 6 times, so the test harness must differ from production in a way that needs a look.
- [ ] **Step 4: Commit the reds disabled.** Put `@Disabled("red until Task 2 — Issue 38 reproduced: see commit body")` on the two red methods only. Commit message: `test(store-postgres): reproduce Issue 38 — closing the wake listener on a Hikari pool logs a WARN and evicts its connection`. The body holds the FAIL lines and the k-of-5 count.

---

### Task 2: Close without an interrupt; the poll thread releases its own connection (Issue 38)

**Files:**
- Create: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/notify/ScriptedPgDataSource.java` (test helper, package-private).
- Create: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/notify/PostgresWakeListenerCloseOrderTest.java`
- Modify: `PostgresWakeListener.java`:
  - fields: `private final Duration closeJoin`, `private final CountDownLatch stop = new CountDownLatch(1)`;
  - constants as in D-1;
  - the package-private 3-argument constructor; the public one delegates with `CLOSE_JOIN`;
  - `close()` (`:146-171`) and `pollLoop()` (`:175-221`) as in D-1;
  - new `awaitStop` and `releaseConnection`;
  - Javadoc: the class "Connection" and "Thread Safety" sections (`:32-50`), and `close()`'s, which reads: "Stops the poll thread without interrupting it, and waits for it for at most two seconds. The poll thread leaves within one poll (500 ms), issues `UNLISTEN *` and returns its connection to the pool. If it has not left in time, this method returns anyway and the thread releases the connection when its current database call returns. Idempotent."
- Modify: `PostgresWakeListenerShutdownTest.java`: remove the two `@Disabled`.

**Interfaces:**
- Produces (package-private, consumed by WP4 Task 5): `PostgresWakeListener(DataSource, String, Duration)` and `private boolean awaitStop(long millis) throws InterruptedException`.
- `ScriptedPgDataSource` (no database). It implements `DataSource` and builds `java.lang.reflect.Proxy`s for `Connection`, `Statement` and `PGConnection`. Any method not listed below throws `UnsupportedOperationException`, so an unexpected call fails loudly.
  - `static final String CHANNEL = "maestro_signal_wake_scripted_0000000"`.
  - `PostgresWakeListener newListener()` returns `new PostgresWakeListener(this, CHANNEL, Duration.ofMillis(300))`.
  - `getConnection()` throws `SQLException("refused (scripted)")` while `refuseConnections(true)` is set; otherwise it returns the one scripted connection.
  - `Connection`: `setAutoCommit` does nothing. `createStatement()` returns a statement whose `execute(sql)` records `sql` as an event, or records `sql + " failed: closed"` and throws `new PSQLException("This connection has been closed.", PSQLState.CONNECTION_DOES_NOT_EXIST)` once the connection is broken. `unwrap(PGConnection.class)` returns the `PGConnection` proxy. `isWrapperFor` returns `true`. `setNetworkTimeout(executor, ms)` records `"setNetworkTimeout " + ms`. `close()` records `"close"`.
  - `PGConnection.getNotifications(int timeoutMillis)`:
    - records `"poll"` and appends `System.nanoTime()` to `pollStartNanos`;
    - if `blockPolls()` is in effect, sets `inBlockedPoll()` to `true` and waits for `releasePolls()`, ignoring interrupts (a stuck driver call);
    - if a failure is scripted (`failPollsWith(Supplier<? extends Exception>)` for every poll; `failNextPollWith(…)` for one, evaluated after a blocked poll is released), throws it;
    - otherwise `BlockingQueue.poll(timeoutMillis, MILLISECONDS)` takes at most **one** queued notification. If that wait is interrupted, it records `"poll-interrupted"`, marks the connection broken (as the closed socket does, Verified 2) and throws `new PSQLException("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE)`;
    - returns `null` or a one-element array.
  - `void deliver(String workflowId, String signalName)` queues `new org.postgresql.core.Notification(CHANNEL, 0, WakeChannel.encode(workflowId, signalName))`.
  - `List<String> events()`, `List<String> eventThreads()` (the thread name for each event, same index), `List<Long> pollStartNanos()`, `int pollCalls()`, `boolean inBlockedPoll()`: copies or volatile reads, so they are thread-safe.

- [ ] **Step 1: Add the 3-argument constructor only.** `closeJoin` replaces `CLOSE_JOIN_MS` in today's `close()`, with no other change, so that the reds below compile against today's behaviour.
- [ ] **Step 2: Write `ScriptedPgDataSource`**, and then `PostgresWakeListenerCloseOrderTest`:

```java
@Test void closeNeverInterruptsAConnectedPoll() {
    var listener = scripted.newListener();
    listener.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
    listener.close();
    assertEquals(List.of(), scripted.events().stream().filter("poll-interrupted"::equals).toList());
}
@Test void theSessionIsUnlistenedAfterTheLastPollThenClosed() {
    var listener = scripted.newListener();
    listener.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
    listener.close();
    var events = scripted.events();
    assertEquals(List.of("setNetworkTimeout 1000", "UNLISTEN *", "close"),
            events.subList(events.lastIndexOf("poll") + 1, events.size()));
    assertFalse(listener.isConnected());
}
@Test void theConnectionIsTouchedOnlyByThePollThreadAfterStart() {
    var listener = scripted.newListener();
    listener.start();                                   // LISTEN runs on this thread, before the poll thread exists
    var fromHere = scripted.events().size();
    await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
    listener.close();
    assertEquals(Set.of("maestro-pg-wake-listener"),
            Set.copyOf(scripted.eventThreads().subList(fromHere, scripted.eventThreads().size())));
}
@Test void aPollThreadStuckPastTheJoinKeepsItsConnectionUntilItReturns() {
    var listener = scripted.newListener();
    listener.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
    scripted.blockPolls();
    await().atMost(Duration.ofSeconds(5)).until(scripted::inBlockedPoll);
    var started = System.nanoTime();
    listener.close();
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0, "close() is bounded");
    assertEquals(List.of(), scripted.events().stream().filter(e -> e.startsWith("UNLISTEN") || e.equals("close")).toList(),
            "nobody may UNLISTEN or close a connection whose poll is still in flight");
    scripted.releasePolls();
    await().atMost(Duration.ofSeconds(5)).until(() -> scripted.events().contains("close"));
    assertEquals(List.of("setNetworkTimeout 1000", "UNLISTEN *", "close"),
            scripted.events().subList(scripted.events().size() - 3, scripted.events().size()));
}
@Test void aSessionThatFailsWhileStoppingIsClosedWithoutUnlisten() {
    var listener = scripted.newListener();
    listener.start();
    await().atMost(Duration.ofSeconds(5)).until(() -> scripted.pollCalls() >= 1);
    scripted.blockPolls();
    await().atMost(Duration.ofSeconds(5)).until(scripted::inBlockedPoll);
    scripted.failNextPollWith(() -> new PSQLException("I/O", PSQLState.CONNECTION_FAILURE));
    listener.close();                                   // join times out (300 ms)
    scripted.releasePolls();                            // the in-flight poll now fails
    await().atMost(Duration.ofSeconds(5)).until(() -> scripted.events().contains("close"));
    assertEquals(List.of(), scripted.events().stream().filter(e -> e.startsWith("UNLISTEN")).toList());
}
@Test void aCloseDuringABackOffReturnsAtOnce() {
    scripted.refuseConnections(true);
    var listener = scripted.newListener();
    listener.start();                                   // connect fails; the poll thread waits 1 s
    var started = System.nanoTime();
    listener.close();
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(250)) < 0);
}
@Test void theNetworkTimeoutIsSetBeforeUnlisten() {
    // as theSessionIsUnlistenedAfterTheLastPollThenClosed; assert indexOf("setNetworkTimeout 1000") < indexOf("UNLISTEN *")
}
```

  In `aSessionThatFailsWhileStoppingIsClosedWithoutUnlisten`, `failNextPollWith` applies to the poll that is released, which is the one in flight. That is why the helper evaluates a scripted failure after a blocked poll lets go.
- [ ] **Step 3: Run red.** `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*PostgresWakeListenerCloseOrderTest'`. Expected FAILs on today's `close()`:
  - `closeNeverInterruptsAConnectedPoll`: `expected: <[]> but was: <[poll-interrupted]>`;
  - `theSessionIsUnlistenedAfterTheLastPollThenClosed`: `expected: <[setNetworkTimeout 1000, UNLISTEN *, close]> but was: <[poll-interrupted, UNLISTEN * failed: closed, close]>`;
  - `theConnectionIsTouchedOnlyByThePollThreadAfterStart`: `expected: <[maestro-pg-wake-listener]> but was: <[maestro-pg-wake-listener, Test worker]>`;
  - `aPollThreadStuckPastTheJoinKeepsItsConnectionUntilItReturns`: the second assertion, `expected: <[]> but was: <[UNLISTEN *, close]>`;
  - `aSessionThatFailsWhileStoppingIsClosedWithoutUnlisten`: `expected: <[]> but was: <[UNLISTEN *]>`;
  - `theNetworkTimeoutIsSetBeforeUnlisten`: an `indexOf` of `-1`.

  `aCloseDuringABackOffReturnsAtOnce` passes today, because the interrupt ends the sleep. It is the latch's pin: Step 7 mutation 2.
- [ ] **Step 4: Implement** D-1 and the Files list.
- [ ] **Step 5: Remove the two `@Disabled`** in `PostgresWakeListenerShutdownTest`.
- [ ] **Step 6: Run green.** `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*PostgresWakeListenerCloseOrderTest' --tests '*PostgresWakeListenerShutdownTest'`, the second one five times with `--rerun`. Then `--tests '*PostgresWakeListener*' --tests '*PostgresSignalNotifierTest' --tests '*PostgresSignalWakeAutoConfigurationTest'`. Expected: PASS every time.
- [ ] **Step 7: Mutation checks** (each reverted with `git checkout -- <file>`; `git diff` must then be empty for that file).

| Mutation | Expected red |
|---|---|
| Put `thread.interrupt()` back in `close()` | `closeNeverInterruptsAConnectedPoll`; Task 1's two tests |
| `awaitStop(backoff)` back to `Thread.sleep(backoff)` (no interrupt now) | `aCloseDuringABackOffReturnsAtOnce` (≈ 300 ms, the join bound) |
| Skip `UNLISTEN *` in `releaseConnection` | `theConnectionReturnedToThePoolListensOnNothing` (`expected: <0> but was: <1>`, Verified 5); `theSessionIsUnlistenedAfterTheLastPollThenClosed` |
| Move `releaseConnection()` from the poll thread's `finally` into `close()`, after the join | `theConnectionIsTouchedOnlyByThePollThreadAfterStart`; `aPollThreadStuckPastTheJoinKeepsItsConnectionUntilItReturns` |
| Drop `setNetworkTimeout` | `theNetworkTimeoutIsSetBeforeUnlisten` |

- [ ] **Step 8: Commit:** `fix(store-postgres): close the wake listener without interrupting its poll; the poll thread UNLISTENs and returns its connection (Issue 38)`. The body holds the Step 3 FAIL lines and the Step 7 table with results.

---

### Task 3: Pin the Spring destroy order with Hikari

**Files:** `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/config/PostgresSignalWakeAutoConfigurationTest.java`. New method and a nested `@Configuration` only; no existing line changes.

- [ ] **Step 1: Write the test.**
  - A nested `@Configuration(proxyBeanMethods = false) static class HikariPool` declares `@Bean(destroyMethod = "close") HikariDataSource dataSource()` over the class's `postgres` container, with `poolName("wake-context-test")` and `maximumPoolSize(4)`.
  - A `ListAppender` sits on `com.zaxxer.hikari` and `io.b2mash.maestro.store.postgres.notify.PostgresWakeListener`, both set to `INFO` for the test, and records `logger + " " + formattedMessage`.

  Use a new runner, built like `runner` (`:49-57`) but with `.withUserConfiguration(HikariPool.class)` in place of `.withBean(DataSource.class, …)`.

```java
@Test
@DisplayName("closing the context logs no pool WARN, and the listener closes before the pool shuts down (Issue 38)")
void closingTheContextLogsNoPoolWarnAndClosesTheListenerBeforeThePool() {
    hikariRunner.run(ctx -> {
        assertThat(ctx).hasNotFailed();
        await().atMost(Duration.ofSeconds(10)).until(ctx.getBean(PostgresWakeListener.class)::isConnected);
    });                                                 // the runner closes the context here
    assertThat(log.stream().filter(l -> l.startsWith("com.zaxxer.hikari") && l.contains("WARN")).toList()).isEmpty();
    var listenerClosed = indexOf(log, "PostgresWakeListener", "closed");
    var poolShutdown = indexOf(log, "HikariDataSource", "Shutdown initiated");
    assertThat(listenerClosed).isNotNegative().isLessThan(poolShutdown);
}
```

  `log` records the level too (`event.getLevel() + " " + logger + " " + message`), so the `WARN` filter reads the level.
- [ ] **Step 2: Run it.** `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*PostgresSignalWakeAutoConfigurationTest'`. Expected: PASS (this is a pin of Verified 7 and of Task 2). If the order assertion fails, Verified 7 is wrong: stop and report the observed order before changing anything.
- [ ] **Step 3: Mutation (test-side).** In `HikariPool`, add a `@Bean SmartLifecycle` whose `stop()` closes the `HikariDataSource`. That closes the pool during the lifecycle stop, before any bean is destroyed. Expected: the order assertion goes red (`poolShutdown < listenerClosed`), and a Hikari `WARN` appears as well (the pool aborted the listener's connection under it). This proves the test can see the order. Revert.
- [ ] **Step 4: Commit:** `test(store-postgres): pin that closing a context closes the wake listener before a Hikari pool, with no pool WARN (Issue 38)`.

---

### Task 4: Docs, release note, issue, gates

**Files:** `docs/configuration.md`, `docs/open-issues.md`, `docs/release-notes.md`, `CLAUDE.md`.

- [ ] **Step 1: `docs/configuration.md`**, the cross-node wake paragraph (`:749-756`). After "…and reconnects with 1 s → 30 s backoff if it drops.", add:

  > At shutdown the listener stops polling within one poll (500 ms; bounded at 2 s), issues `UNLISTEN *` and returns its connection to the pool intact: it is closed with the application context's beans, after the engine has drained and before the `DataSource` is shut down. A pooled connection is never returned still listening.

- [ ] **Step 2: `docs/open-issues.md` Issue 38.** Add a `> **Resolved** (WP10, <PR>).` callout naming the logger (`com.zaxxer.hikari.pool.ProxyConnection`, SQLSTATE `08003`) and the tests: `PostgresWakeListenerShutdownTest`, `PostgresWakeListenerCloseOrderTest`, `PostgresSignalWakeAutoConfigurationTest.closingTheContextLogsNoPoolWarnAndClosesTheListenerBeforeThePool`. Set the §4 index row's status to **Resolved**.
- [ ] **Step 3: `docs/release-notes.md`.** Add a new first subsection under `## Unreleased — Postgres + Kafka only`: `### Fixed — no pool WARN when the Postgres wake listener closes (Issue 38)`. Its bullets:
  - **What was wrong:** Verified 1–2 in two sentences, quoting the `WARN`.
  - **What changes:** D-1 in two sentences. Shutdown waits up to one poll (≤ 500 ms; bounded at 2 s) longer. The pool keeps its connection.
  - **Nothing to configure.** `maestro.signal.wake-notify.enabled=false` deployments are unaffected.
- [ ] **Step 4: `CLAUDE.md`**, "Lock Keys", the sentence "The listener holds one `DataSource` connection for the app lifetime". Append: "; at shutdown its poll thread `UNLISTEN`s and returns it to the pool (no interrupt, Issue 38)".
- [ ] **Step 5: Hand-offs.** To WP5's owner: D-3's grace-arithmetic line. To WP4's owner: Task 2's Interfaces are final, and `pollLoop`'s `RuntimeException` arm and `Error` handling are left for WP4 Task 5. The coordinator records both in the review section.
- [ ] **Step 6: Javadoc.** Run `./gradlew --no-watch-fs :maestro-store-postgres:javadoc`. Expected: no warning naming `PostgresWakeListener`.
- [ ] **Step 7: Commit:** `docs: Issue 38 resolved — the wake listener closes cleanly on a pooled DataSource`.
- [ ] **Step 8: Coordinator gates.**
  - `./gradlew --no-watch-fs build`, detached with a log header;
  - nightly E2E on the branch (`gh workflow run e2e-nightly.yml --ref fix/wake-listener-shutdown`). The loan services run on Hikari, so a shutdown `WARN` in their logs is the field check: `grep -c 'marked as broken' <service logs>` must be 0;
  - chaos PR-gate ×3.

  Results go in the review section with their log identity.

---

## Self-review (run 2026-11-17)

- **Spec coverage.**
  - Join the poll thread, then `UNLISTEN`, with no interrupt of a connected poll: D-1, Task 2.
  - "Reproduce the WARN first": done at planning time (6 of 6), committed as Task 1's reds before any fix.
- **The brief's questions.**
  - Today's sequence step by step: "Today's shutdown sequence".
  - What the interrupt does to `getNotifications`: Verified 2.
  - Who logs the `WARN`: Verified 1.
  - The reproduction: Task 1.
  - Stop flag, join timeout and its relation to `maestro.shutdown.timeout`: D-1, D-3.
  - A clean return to the pool, and what happens today: Verified 3 and 5, D-2.
  - Join timeout, database down, PgBouncer: D-2.
  - Context restart and phases: D-3.
- **Type consistency.** These names are the same in every task and in WP4 Task 5:
  - `PostgresWakeListener(DataSource, String, Duration)`, `awaitStop`, `releaseConnection`, `CLOSE_JOIN`, `UNLISTEN_TIMEOUT_MS`;
  - `ScriptedPgDataSource.newListener/deliver/failPollsWith/failNextPollWith/blockPolls/releasePolls/inBlockedPoll/refuseConnections/events/eventThreads/pollStartNanos/pollCalls`.
- **Proportion.** The code blocks are tests, log lines and doc text. The listener's new bodies are fixed by D-1's steps and the ordering tests.

## Review section

(The coordinator fills this in: Task 1's k-of-5 red count and FAIL lines; Task 2's red and mutation results; Task 3's observed order; gate results with log identity; the two hand-offs.)
