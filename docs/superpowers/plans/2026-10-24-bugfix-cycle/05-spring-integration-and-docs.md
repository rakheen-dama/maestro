# Spring Integration and Documentation Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the paved path honest: one workflow object per run, Maestro-owned schema migrations that cannot collide with the host's, a real per-queue concurrency bound, a usable client handle, engine internals off the public context, and README/getting-started/configuration/admin docs that describe what the code does.

**Architecture:** `WorkflowRegistration` carries a factory instead of a singleton, so every start, re-adoption and retry gets a fresh workflow object that the running-workflow record owns for queries. `maestro-store-postgres` gains a `MaestroSchemaInitializer` that runs its own Flyway over module-specific classpath locations into `maestro_schema_history`, leaving the host's Flyway alone. A framework-free `TaskQueueGovernor` in `maestro-core` holds one `Semaphore` pair per configured task queue; the starter binds `maestro.worker.*` into it. The task-dispatch channel (`publishTask`/`subscribe`, `maestro.tasks.*`, `maestro_task_queue`) and the unread `maestro_lifecycle_event_queue` are deleted. Docs follow each behaviour change in the task that makes it.

**Tech Stack:** Java 25, Spring Boot 4.0.5 (`AutowireCapableBeanFactory`, prototype bean scope, `ApplicationContextRunner`), Flyway 11.14.1 (`Flyway.configure().table(...).locations(...)`), Gradle 9 `java-platform` plugin, JUnit 5, Testcontainers 2 (Postgres 16), Awaitility.

**Spec:** [`00-spec-independent-assessment.md`](00-spec-independent-assessment.md) §4 rows F1, F5, F6, F8, F9, F10, F11, F12, F13; §5 (ease of use); §7 (README accuracy); `docs/open-issues.md` § Issue 25. Decisions already taken by the cycle coordinator are restated inline where a task implements them; do not re-open them.

## Global Constraints

See [`README.md` § Global Constraints](README.md#global-constraints). Additionally for this sub-plan:

- Plans 01–04 have landed before this one. Where a step names "Plan 02's exception-reconstruction factory" or "Plan 04's durable `awaitSignal` timer", read that plan's **Interfaces** block for the exact type and method; this plan does not restate them.
- New Flyway files go in the **new** locations introduced by Task 4 (`db/maestro/store`, `db/maestro/lock`, `db/maestro/messaging`), still in the version bands store 1–99, lock 100–199, messaging 200–299. Moving an existing migration file is allowed (the checksum is content-based); editing one — including its comments — is not.
- Every removed property (`maestro.store.table-prefix`, `maestro.messaging.topics.tasks`) fails startup with a message that names the replacement, rather than binding silently to nothing (spec §6: "~10 'config that reads correctly and does nothing'" defects).
- Doc copy marked **verbatim** below is the spec's fixed wording; copy it exactly.

## Review Focus

Inputs this sub-plan's spec implies but which are easy to leave untested; each gets a pin in the owning task.

1. **Two nodes race the per-run workflow object** (README Review Focus 4): node A starts a workflow and dies holding the instance lock; node B re-adopts after TTL. A query on B must answer from B's run object; A's object must be unreachable through `queryWorkflow`. — Task 1, `MultiNodePerRunQueryIT`.
2. **Host application with its own `V1__init.sql` in `classpath:db/migration`** (README Review Focus 5) boots alongside Maestro's isolated history with no version collision; `flyway_schema_history` holds exactly the host's row. — Task 4, `MaestroMigrationsCoexistIT`.
3. **A user-declared singleton `@Component` workflow bean** must fail startup with a message naming the fix (`@Scope("prototype")` or drop `@Component`), never silently share state across runs again. — Task 2, `WorkflowRegistrarPrototypeTest.singletonWorkflowBeanFailsStartup`.
4. **A parked workflow on a bounded queue** must release its slot so the (concurrency+1)-th run proceeds, and must reacquire before its own code resumes. — Task 5, `WorkflowExecutorConcurrencyTest.parkedWorkflowReleasesItsSlot`.
5. **`startAndWait` on a workflow an operator terminates mid-wait** must throw `WorkflowExecutionException` whose message carries the termination reason, never a bare `"Workflow ended with status TERMINATED"`. — Task 7, `MaestroClientTest.startAndWaitOnTerminatedWorkflowCarriesTheReason`.

---

### Task 1: Per-run workflow objects in the core engine (F1, core half)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowRegistration.java:20-25`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:392-399` (`startWorkflow`), `:470-474` (`resumeWorkflow`), `:515`, `:991` (registration callers), `:1183-1217` (`queryWorkflow` Javadoc + body), `:1311-1318` and `:1377-1393` (`launchWorkflow` thread body and `RunningWorkflow` creation), `:2007-2008` (`RunningWorkflow` record)
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/TestWorkflowEnvironment.java:170-193` (`startWorkflow`), `:311-323` (`instantiate`), `:326-376` (`wireActivityStubs`)
- Modify: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/support/MaestroEngineHarness.java:136-142` (`registerWorkflow`), `:153-160` (`start`)
- Modify (mechanical): every `executor.startWorkflow(...)`/`resumeWorkflow(...)` call in `maestro-core/src/test` (112 call sites, 25 files) — the fifth argument becomes `() -> workflow`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowExecutorPerRunInstanceTest.java` (create)
- Test: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/multinode/MultiNodePerRunQueryIT.java` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `public record WorkflowRegistration(String workflowType, String taskQueue, Supplier<Object> workflowFactory, Method workflowMethod)`. Javadoc on `workflowFactory`: *called exactly once per run — on `startWorkflow`, on every re-adoption by `recoverWorkflows`/`resumeWorkflow`, and on `retryWorkflow`; the returned object is held in the running-workflow record for the life of that run and is the receiver for every `@QueryMethod` invocation against it; it is never reused across runs.*
  - `public UUID WorkflowExecutor.startWorkflow(String workflowId, String workflowType, String taskQueue, @Nullable Object input, Supplier<Object> workflowFactory, Method workflowMethod)`
  - `public boolean WorkflowExecutor.resumeWorkflow(WorkflowInstance instance, Supplier<Object> workflowFactory, Method workflowMethod)`
  - `MaestroEngineHarness.registerWorkflow(Class<?> workflowClass)` (reflective no-arg construction per run) and `registerWorkflow(Class<?> workflowClass, Supplier<?> factory)`; both wire `@ActivityStub` fields on the object the factory returns.
  - `TestWorkflowEnvironment.startWorkflow(...)` signatures unchanged; internally supplies `() -> wire(instantiate(workflowClass))` per run.

- [ ] **Step 1: Write the failing core test**

`WorkflowExecutorPerRunInstanceTest` (same fixtures style as `WorkflowExecutorQueryTest.java:48-80` — `InMemoryWorkflowStore`, `NoOpMessaging`, a workflow whose `@WorkflowMethod run(String label)` sets `volatile String step = label` then parks on `awaitSignal("go", String.class, Duration.ofMinutes(1))`, and `@QueryMethod String step()`):

```java
@Test
void eachStartInvokesTheFactoryAndQueriesAnswerFromThatRunsObject() throws Exception {
    var created = new AtomicInteger();
    Supplier<Object> factory = () -> { created.incrementAndGet(); return new ProbeWorkflow(); };
    var method = ProbeWorkflow.class.getMethod("run", String.class);
    executor.registerQueries("probe", ProbeWorkflow.class);

    executor.startWorkflow("p-a", "probe", "default", "A", factory, method);
    executor.startWorkflow("p-b", "probe", "default", "B", factory, method);
    await().atMost(5, SECONDS).until(() -> executor.parkedCount() == 2);

    assertEquals(2, created.get(), "one workflow object per run");
    assertEquals("A", executor.queryWorkflow("p-a", "step", null, String.class));
    assertEquals("B", executor.queryWorkflow("p-b", "step", null, String.class));
}

@Test
void resumeInvokesTheFactoryAgainAndDiscardsTheOldObject() throws Exception {
    // start, let it park, shut this executor down (park abandoned, status WAITING_SIGNAL), build a second executor over the same store
    // resumeWorkflow(instance, factory, method) → created == 2 and the second executor's query returns the replayed label
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :maestro-core:test --tests '*WorkflowExecutorPerRunInstanceTest*' --console=plain`
Expected: compilation failure — `startWorkflow(String,String,String,Object,Supplier,Method)` does not exist.

- [ ] **Step 3: Change `WorkflowRegistration` and the executor**

In `WorkflowRegistration.java` replace `Object workflowImpl` with `Supplier<Object> workflowFactory` and write the Javadoc quoted in Interfaces. In `WorkflowExecutor`: `startWorkflow`/`resumeWorkflow` take `Supplier<Object> workflowFactory`; `launchWorkflow` takes the supplier and calls `workflowFactory.get()` exactly once, **before** the virtual thread is created (so a throwing factory surfaces to the caller of `startWorkflow` and the instance lock is released in the existing `catch (RuntimeException | Error e)` at `:1395-1399`); the obtained object goes into `new RunningWorkflow(thread, instance, workflowType, workflowImpl)` at `:1392` and into `executeWorkflow`. `recoverWorkflows` (`:515`) and `retryWorkflow` (`:991`) pass `reg.workflowFactory()`. `queryWorkflow` body is unchanged (it already reads `running.workflowImpl()` at `:1216`); replace its Javadoc paragraph at `:1183-1188` with: *"Queries are answered only by the node whose executor currently holds this workflow's run — the one that holds its instance lock. A workflow that is parked, running or compensating on another node, or that has reached a terminal status, throws `WorkflowNotQueryableException`. Cross-node query routing is out of scope; route the request to the owning node or read durable state via `WorkflowHandle.status()`/`result()`."*

- [ ] **Step 4: Mechanically update test call sites**

Run from repo root: `grep -rln "\.startWorkflow(\|\.resumeWorkflow(" maestro-core/src/test` and change the workflow-instance argument to a supplier (`workflow` → `() -> workflow`). Where a test asserts on the *same* object the executor ran (e.g. reads a field of `workflow` after completion), the supplier still returns that pre-built object, so the assertions keep working.

- [ ] **Step 5: Update `TestWorkflowEnvironment` and `MaestroEngineHarness`**

`TestWorkflowEnvironment.startWorkflow(String, Class<?>, Object)` (`:170-193`): replace the eager `instantiate` + `wireActivityStubs` with `Supplier<Object> factory = () -> { var o = instantiate(workflowClass); wireActivityStubs(o); return o; }` and pass it to the executor. `MaestroEngineHarness.registerWorkflow(Object)` (`:136-142`) becomes the two overloads in Interfaces; `start` (`:153-160`) passes `registration.workflowFactory()`. Update every `registerWorkflow(new XWorkflow())` call in `maestro-integration-tests/src/test` to `registerWorkflow(XWorkflow.class)` (grep `registerWorkflow(new`).

- [ ] **Step 6: Run core, test-kit and starter suites**

Run: `./gradlew :maestro-core:test :maestro-test:test --console=plain`
Expected: BUILD SUCCESSFUL, `WorkflowExecutorPerRunInstanceTest` 2 tests passed. (`:maestro-spring-boot-starter` will not compile until Task 2 — expected; do not run it yet.)

- [ ] **Step 7: Write the Review Focus 1 integration pin**

`MultiNodePerRunQueryIT extends PostgresIntegrationSupport`, modelled on `MultiNodeOwnerDeathIT.java:60-140` (its `DyingLock`, `WedgingChainActivities`, `SHORT_TTL`, latches). Fixture workflow `TaggedChainWorkflow(String nodeTag)` in `maestro-integration-tests/.../workflows/TestWorkflows.java`: `@QueryMethod String tag()` returns the constructor argument; `@WorkflowMethod` runs `chain.stepOne/stepTwo/stepThree` like `ChainWorkflow`. Node A registers `registerWorkflow(TaggedChainWorkflow.class, () -> new TaggedChainWorkflow("node-a"))`, node B with `"node-b"`.

```java
@Test
void afterReadoptionQueriesAnswerFromNodeBsObjectAndNodeAsIsUnreachable() throws Exception {
    var workflowId = startAndAbandonOnNodeA(SHORT_TTL);           // wedged in stepTwo, lock dead
    assertEquals("node-a", nodeA.executor().queryWorkflow(workflowId, "tag", null, String.class));

    nodeB = node("node-b", ...); nodeB.startRecoveryPoller(Duration.ofMillis(200));
    await().atMost(Duration.ofSeconds(30)).until(() -> nodeB.executor().isRunning(workflowId));
    assertEquals("node-b", nodeB.executor().queryWorkflow(workflowId, "tag", null, String.class));

    wedge.countDown(); nodeA.close();                              // A's zombie exits; A's run is gone
    assertThrows(WorkflowNotQueryableException.class,
            () -> nodeA.executor().queryWorkflow(workflowId, "tag", null, String.class));
    awaitStatus(workflowId, WorkflowStatus.COMPLETED, Duration.ofSeconds(30));
}
```

Node B's `stepTwo` must block on a second latch until the B-side query has been asserted, so B is still running when queried.

- [ ] **Step 8: Run the integration pin**

Run: `./gradlew :maestro-integration-tests:test --tests '*MultiNodePerRunQueryIT*' --console=plain`
Expected: 1 test passed.

- [ ] **Step 9: Release notes, commit**

Under `docs/release-notes.md` `## Unreleased`, add `### Changed — One workflow object per run` with a paragraph stating the old behaviour (one Spring singleton per workflow type shared by every concurrent instance, F1) and the new contract (factory per run; `@QueryMethod` answers from that run's object; single-node query limitation now documented).

```bash
git add maestro-core maestro-test maestro-integration-tests docs/release-notes.md
git commit -m "fix(core): instantiate a workflow object per run via WorkflowRegistration.workflowFactory (F1)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Prototype-scoped workflow beans in the starter, docs rewrite, sample tests (F1, Spring half)

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/DurableWorkflowBeanRegistrar.java:98-104` (set scope before `registerBeanDefinition`), class Javadoc `:21-23`, `:43-47`
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/WorkflowRegistrar.java:59-70` (discovery), `:73-112` (registration)
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/proxy/ActivityStubBeanPostProcessor.java:127-198` (proxy cache), `:195-197` (log level)
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/client/WorkflowStub.java:66-75`
- Modify: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/scanfixture/ComponentAnnotatedWorkflow.java:13` (add `@Scope(BeanDefinition.SCOPE_PROTOTYPE)`)
- Create: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/scanfixture/singleton/SingletonComponentWorkflow.java`
- Test: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/config/WorkflowRegistrarPrototypeTest.java` (create); add tests to `.../client/MaestroClientTest.java`
- Modify docs: `README.md:52-83` (Quick Start step 3 — no code change, add one sentence after the snippet), `docs/getting-started.md:396-433` (Step 8 snippet comment + "Key details"), `docs/concepts.md:36-56` (Durable Workflows key points), `:283-330` (Queries)
- Modify samples: `maestro-samples/sample-postgres-only/src/main/java/.../DocumentApprovalWorkflow.java:42-43`, `maestro-samples/sample-order-service/src/main/java/.../OrderFulfilmentWorkflow.java:39-40`, `maestro-samples/sample-loan-origination/loan-application-service/src/main/java/.../LoanApplicationWorkflow.java:67`, `.../src/v2/java/.../LoanApplicationWorkflow.java:103`, `.../underwriting-service/.../UnderwritingWorkflow.java:94` (comment only)
- Modify: `maestro-samples/sample-postgres-only/build.gradle.kts`, `maestro-samples/sample-order-service/build.gradle.kts`, `maestro-samples/sample-payment-gateway/build.gradle.kts` — add `testImplementation(project(":maestro-test"))`
- Test (create): `maestro-samples/sample-postgres-only/src/test/java/io/b2mash/maestro/samples/document/workflow/DocumentApprovalWorkflowTest.java`, `maestro-samples/sample-order-service/src/test/java/io/b2mash/maestro/samples/order/workflow/OrderFulfilmentWorkflowTest.java`, `maestro-samples/sample-payment-gateway/src/test/java/io/b2mash/maestro/samples/payment/workflow/PaymentProcessingWorkflowTest.java` (if Plan 03 already created this file, add the two methods to it)

**Interfaces:**
- Consumes: Task 1's `WorkflowRegistration.workflowFactory`, `WorkflowExecutor.startWorkflow(..., Supplier<Object>, Method)`.
- Produces: `@DurableWorkflow` bean definitions registered by `DurableWorkflowBeanRegistrar` are **prototype**-scoped; `WorkflowRegistrar` builds `workflowFactory = () -> applicationContext.getBean(beanName)` and throws `BeanInitializationException` for any `@DurableWorkflow` bean whose merged definition is singleton-scoped with message `"@DurableWorkflow bean '%s' (%s) must be prototype-scoped: a new workflow object is created per run. Remove @Component (the starter registers the class itself) or add @Scope(\"prototype\")."`.

**Decision record (do not re-open):** the coordinator offered `AutowireCapableBeanFactory.createBean(targetClass)` *or* prototype bean definitions. Reading `DurableWorkflowBeanRegistrar.java:90-104`: the scan already produces bean definitions and `WorkflowRegistrar` discovers workflows through the bean factory, and users may legitimately declare workflow beans themselves (`ComponentAnnotatedWorkflow` fixture). Prototype scope keeps both paths, keeps `@Autowired` fields and `ActivityStubBeanPostProcessor` working unchanged (Spring applies `BeanPostProcessor`s to every `getBean` of a prototype), and gives one place to reject singleton misuse. `getBean(beanName)` is therefore the factory; `createBean` is not used.

- [ ] **Step 1: Write the failing starter tests**

`WorkflowRegistrarPrototypeTest` using the `ApplicationContextRunner` from `DurableWorkflowScanTest.java:35-41` with `maestro.workflow-packages=io.b2mash.maestro.spring.config.scanfixture`:

```java
@Test void scannedWorkflowDefinitionIsPrototype() {
    runner.run(ctx -> assertThat(ctx.getBeanFactory().getBeanDefinition("scannedApprovalWorkflow").isPrototype()).isTrue());
}
@Test void factoryReturnsANewFullyWiredObjectEveryTime() {
    runner.run(ctx -> {
        var reg = ctx.getBean(WorkflowRegistrar.class).getRegistration(ScannedApprovalWorkflow.class);
        var first = (ScannedApprovalWorkflow) reg.workflowFactory().get();
        var second = (ScannedApprovalWorkflow) reg.workflowFactory().get();
        assertThat(first).isNotSameAs(second);
        assertThat(Proxy.isProxyClass(stubField(first).getClass())).isTrue();   // @ActivityStub injected on a prototype
        assertThat(stubField(first)).isSameAs(stubField(second));               // proxy cached per field, not rebuilt per run
    });
}
@Test void discoveryDoesNotInstantiateWorkflows() {
    // ScannedApprovalWorkflow gets a static AtomicInteger constructed; after context refresh it must be 0
}
@Test void singletonWorkflowBeanFailsStartup() {
    runner.withPropertyValues("maestro.workflow-packages=io.b2mash.maestro.spring.config.scanfixture.singleton")
          .run(ctx -> { assertThat(ctx).hasFailed();
                        assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("must be prototype-scoped"); });
}
```

`SingletonComponentWorkflow` is `@Component @DurableWorkflow(name = "singleton-component")` with a trivial `@WorkflowMethod`. In `MaestroClientTest` add nested `PerRunState`:

```java
@Test void twoConcurrentInstancesKeepSeparateQueryState() {
    // StatusProbeWorkflow: volatile String step; run(String label){ step = label; WorkflowContext.current().awaitSignal("go", String.class, Duration.ofMinutes(1)); } @QueryMethod String step()
    client.newWorkflow(StatusProbeWorkflow.class, options("probe-a")).startAsync("A");
    client.newWorkflow(StatusProbeWorkflow.class, options("probe-b")).startAsync("B");
    await().until(() -> executor.parkedCount() == 2);
    assertEquals("A", client.getWorkflow("probe-a").query("step", String.class));
    assertEquals("B", client.getWorkflow("probe-b").query("step", String.class));
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-spring-boot-starter:test --console=plain`
Expected: compilation failure in `WorkflowRegistrar.java:87-89` and `WorkflowStub.java:72` (`workflowImpl` no longer exists).

- [ ] **Step 3: Implement**

`DurableWorkflowBeanRegistrar.java:101`: `candidate.setScope(BeanDefinition.SCOPE_PROTOTYPE)` before `registry.registerBeanDefinition`; update the Javadoc words "singleton" at `:21`, `:43` to "prototype" and say why. `WorkflowRegistrar.afterSingletonsInstantiated` (`:59-70`): replace `getBeansWithAnnotation` with `ctx.getBeanNamesForAnnotation(DurableWorkflow.class)`, resolve `targetClass = ClassUtils.getUserClass(ctx.getType(beanName))`, check `((ConfigurableApplicationContext) ctx).getBeanFactory().getMergedBeanDefinition(beanName).isSingleton()` → throw the Interfaces message; build `new WorkflowRegistration(workflowType, taskQueue, () -> ctx.getBean(beanName), workflowMethod)`. `ActivityStubBeanPostProcessor.processWorkflowBean` (`:127-198`): add `private final ConcurrentHashMap<Field, Object> proxyCache` and `computeIfAbsent(field, f -> createProxy(...))` so validation and proxy construction happen once per field, then `field.set(bean, proxy)` per instance; move the `logger.info("Processed @DurableWorkflow bean ...")` at `:195-197` inside the compute (once per class) and log per-instance injection at DEBUG. `WorkflowStub.startAsync` (`:66-75`): pass `registration.workflowFactory()`. Add `@Scope(BeanDefinition.SCOPE_PROTOTYPE)` to `ComponentAnnotatedWorkflow`.

- [ ] **Step 4: Run starter tests**

Run: `./gradlew :maestro-spring-boot-starter:test --console=plain`
Expected: BUILD SUCCESSFUL; `WorkflowRegistrarPrototypeTest` 4 passed, `MaestroClientTest` includes `twoConcurrentInstancesKeepSeparateQueryState` passed.

- [ ] **Step 5: Rewrite the docs that teach `currentStep`**

Keep every `private volatile String currentStep` snippet as is (it is now correct), and add this sentence (verbatim) directly under each snippet's key-points list:
- `README.md` after the step-3 snippet (`:83`): *"Maestro creates a new `WelcomeWorkflow` object for every workflow run, so instance fields are per-instance state — two concurrent runs never share them. Fields read by a `@QueryMethod` are still `volatile`, because queries run on the caller's thread."*
- `docs/getting-started.md:431-433` ("Key details"): replace the `@QueryMethod` bullet with *"`@QueryMethod` exposes `getStatus()` for external callers. A new workflow object is created per run, so `currentStep` is this run's state and never another instance's. Query methods run on the caller's thread, so fields they read must be `volatile` or otherwise thread-safe. A query is answered by the node currently running the workflow; see [Queries](concepts.md#queries)."*
- `docs/concepts.md:50-56` (Durable Workflows key points): add bullet *"**One object per run.** The engine instantiates the workflow class for every start, recovery and retry. Instance fields are per-run state; never put per-instance state in `static` fields."*
- `docs/concepts.md` Queries, after the **Thread safety** paragraph (`:325`): add paragraph **Single-node contract** (verbatim): *"A query is answered by the node whose engine is currently running the workflow — the one holding its instance lock. Any other node, and any node after the workflow reaches `COMPLETED`/`FAILED`/`TERMINATED`, throws `WorkflowNotQueryableException`. A load-balanced `GET /status` on a multi-node service therefore fails on the non-owning nodes; route the request to the owner, or serve status from `WorkflowHandle.status()`/`result()` (durable, any node) or your own projection. Cross-node query routing is not implemented."*
- Sample comments at the five `volatile String currentStep` sites: replace `// Volatile: read by @QueryMethod from caller's thread, written by workflow's virtual thread` with `// Per-run state: Maestro creates a new workflow object for every run. Volatile because @QueryMethod runs on the caller's thread.`

- [ ] **Step 6: Write the sample tests (fail first, then pass)**

Add `testImplementation(project(":maestro-test"))` to the three sample builds. Each test class uses `TestWorkflowEnvironment.create()` + Awaitility (already on the test classpath via `maestro.java-conventions`).

`DocumentApprovalWorkflowTest` (register `DocumentActivities` → a test-local `LatchingDocumentActivities` that blocks `assignReviewer` while a `CountDownLatch` keyed on the input title is held; otherwise behaves like `InMemoryDocumentActivities`):
```java
@Test void approvedDocumentIsPublished() {
    var h = env.startWorkflow(DocumentApprovalWorkflow.class, new DocumentInput("T", "body", "author-1"));
    awaitStatus(h, WorkflowStatus.WAITING_SIGNAL);
    h.signal("review.decision", new ReviewDecision(true, "rev-1", null));
    var r = h.getResult(DocumentResult.class, Duration.ofSeconds(10));
    assertTrue(r.approved()); assertEquals("PUBLISHED", r.finalStatus());
}
@Test void twoInstancesReportTheirOwnStep() {
    activities.block("B");
    var a = env.startWorkflow(DocumentApprovalWorkflow.class, new DocumentInput("A", "body", "author-1"));
    var b = env.startWorkflow(DocumentApprovalWorkflow.class, new DocumentInput("B", "body", "author-2"));
    awaitStatus(a, WorkflowStatus.WAITING_SIGNAL);
    assertEquals("AWAITING_REVIEW", a.query("getStatus", null, DocumentStatus.class).currentStep());
    assertEquals("ASSIGNING_REVIEWER", b.query("getStatus", null, DocumentStatus.class).currentStep());
    activities.release("B");
}
```
`OrderFulfilmentWorkflowTest` — same shape with fakes for `InventoryActivities` (latching `reserve` for order `"B"`), `MessagingActivities` (records requests), `ShippingActivities`, `NotificationActivities`: happy path signals `payment.result` with `PaymentResult.success("tx-1")` and asserts `OrderResult.success()`; concurrency asserts `"AWAITING_PAYMENT"` vs `"RESERVING_INVENTORY"`.
`PaymentProcessingWorkflowTest` (no `@QueryMethod` on this workflow): happy path asserts `PaymentProcessingResult.success()` and the recorded `publishPaymentResult` orderId; concurrency wedges `provider.charge` for order `"B"`, asserts A completes with its own `transactionId` while `b.getStatus() == RUNNING`, releases, asserts B completes and the messaging fake recorded two distinct orderIds.

Run: `./gradlew :maestro-samples:sample-postgres-only:test :maestro-samples:sample-order-service:test :maestro-samples:sample-payment-gateway:test --console=plain`
Expected: 6 tests passed (2 per sample).

- [ ] **Step 7: Full starter + samples gate, commit**

Run: `./gradlew :maestro-spring-boot-starter:test :maestro-store-postgres:test --console=plain` → BUILD SUCCESSFUL.

```bash
git add maestro-spring-boot-starter maestro-samples README.md docs/getting-started.md docs/concepts.md
git commit -m "fix(spring): prototype-scoped @DurableWorkflow beans, per-run factory, query docs and sample tests (F1)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Remove `maestro.store.table-prefix`; recommend one schema per service (F6)

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java:201-218` (`StoreProperties`)
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/JdbcStoreConfiguration.java:13-25`, `AbstractJdbcWorkflowStore.java:153`
- Modify: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/config/PostgresStoreAutoConfiguration.java:40-42` (Javadoc), `:65-78`
- Modify tests: `maestro-store-postgres/src/test/.../config/PostgresStoreAutoConfigurationTest.java:87-97`, `maestro-spring-boot-starter/src/test/.../MaestroPropertiesBindingTest.java:93,108,144`, `maestro-store-jdbc/src/test/.../JdbcStoreConfigurationTest.java:26,42,51`
- Modify docs: `docs/configuration.md:40-65` (Store Configuration), `:518` (complete example), `docs/cross-service.md:486-490`, `docs/operations.md:534-562` (§10.6), `docs/maestro-prd.md:383`, `docs/release-notes.md` (`## Unreleased` → `### Removed`)

**Interfaces:**
- Consumes: nothing.
- Produces: `public record StoreProperties(@DefaultValue("postgres") String type)` (Task 4 adds a `schema` component). `public record JdbcStoreConfiguration(ObjectMapper objectMapper)`; `AbstractJdbcWorkflowStore.tableName(String suffix)` returns `"maestro_" + suffix` from a `public static final String TABLE_PREFIX = "maestro_"` on `JdbcStoreConfiguration`. `PostgresStoreAutoConfiguration` throws `IllegalStateException` when `env.containsProperty("maestro.store.table-prefix")` with message (verbatim): `"maestro.store.table-prefix has been removed: Maestro tables are always named maestro_*. To isolate services sharing one database, give each service its own Postgres schema (JDBC URL ...?currentSchema=<schema> or spring.datasource.hikari.schema=<schema>). See docs/configuration.md § Store Configuration."`

- [ ] **Step 1: Write the failing tests**

Replace `honorsTablePrefixProperty` in `PostgresStoreAutoConfigurationTest.java:87-97` with:
```java
@Test void failsStartupWhenTablePrefixIsSet() {
    storeRunner().withBean(DataSource.class, () -> mock(DataSource.class))
        .withPropertyValues("maestro.store.table-prefix=custom_", "maestro.store.schema.mode=none")
        .run(ctx -> { assertThat(ctx).hasFailed();
                      assertThat(ctx.getStartupFailure()).rootCause()
                          .hasMessageContaining("maestro.store.table-prefix has been removed")
                          .hasMessageContaining("currentSchema"); });
}
```
(`maestro.store.schema.mode=none` is a forward reference to Task 4; until Task 4 lands it is an ignored unknown property.) In `MaestroPropertiesBindingTest` delete the `table-prefix` binding lines (`:93`, `:108`, `:144`). In `JdbcStoreConfigurationTest` replace the prefix assertions with `assertEquals("maestro_workflow_event", store.tableName("workflow_event"))`-style checks through a small `AbstractJdbcWorkflowStore` subclass, or delete the prefix tests if `tableName` is private — pick whichever the visibility allows and keep one assertion that the prefix is `maestro_`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-store-postgres:test --tests '*PostgresStoreAutoConfigurationTest*' --console=plain`
Expected: `failsStartupWhenTablePrefixIsSet` FAILS (context starts; the property binds to the store).

- [ ] **Step 3: Implement the removal**

Remove `tablePrefix` from `StoreProperties` (`:201-218`) and its Javadoc; `JdbcStoreConfiguration` loses the prefix parameter and gains `TABLE_PREFIX`; `AbstractJdbcWorkflowStore.java:153` uses it. `PostgresStoreAutoConfiguration.postgresWorkflowStore` (`:68-78`): drop the `TABLE_PREFIX_PROPERTY`/`DEFAULT_TABLE_PREFIX` constants, add the `containsProperty` guard with the verbatim message, construct `new JdbcStoreConfiguration(objectMapper)`; fix the Javadoc at `:40-42`. Fix the `PostgresWorkflowStore(DataSource, ObjectMapper)` convenience constructor if it built a prefixed config.

- [ ] **Step 4: Run store, jdbc and starter suites**

Run: `./gradlew :maestro-store-jdbc:test :maestro-store-postgres:test :maestro-spring-boot-starter:test --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Docs**

- `docs/configuration.md:40-65`: delete the `maestro.store.table-prefix` row (`:47`) and the two paragraphs/callout at `:49-63`; add under the table (verbatim): *"**Isolating services that share a database.** Maestro's tables are always named `maestro_*`. Two services must never share a `maestro_workflow_instance` table, so give each service its own Postgres schema and point its datasource at it — `jdbc:postgresql://host/db?currentSchema=order_service` or `spring.datasource.hikari.schema: order_service`. Maestro's migrations (see [Schema management](#schema-management)) run in the connection's current schema, so no further configuration is needed. `maestro.store.table-prefix` was removed; setting it fails startup."* Delete `table-prefix: maestro_` at `:518`.
- `docs/cross-service.md:486-490`: replace the paragraph with (verbatim): *"**Give each service its own Postgres schema.** Set `currentSchema` in the JDBC URL (or `spring.datasource.hikari.schema`) so each service's `maestro_*` tables live apart. This prevents workflow ID collisions, keeps event tables smaller, and allows independent backup and migration schedules. Two services should never share a `maestro_workflow_instance` table."*
- `docs/operations.md:534-562` §10.6: retitle "10.6 Schema migrations" and replace the body with two sentences: Maestro's migrations always target `maestro_*` tables and are applied by Maestro itself (Task 4 adds the detail); the former custom-prefix mode is gone.
- `docs/maestro-prd.md:383`: delete the `table-prefix: maestro_` line.
- Release notes `### Removed`: `maestro.store.table-prefix` (fails startup if set; reason: migrations and lock/messaging SQL never honoured it — spec F6).

- [ ] **Step 6: Commit**

```bash
git add maestro-store-jdbc maestro-store-postgres maestro-spring-boot-starter docs/configuration.md docs/cross-service.md docs/operations.md docs/maestro-prd.md docs/release-notes.md
git commit -m "fix(store): remove maestro.store.table-prefix; fail fast if set; recommend one schema per service (F6)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Maestro-owned Flyway history via `MaestroSchemaInitializer` (F5, F9)

**Files:**
- Move (`git mv`): `maestro-store-postgres/src/main/resources/db/migration/V1__create_maestro_schema.sql`, `V2__create_indexes.sql`, `V3__timer_lookup_index.sql`, `V4__signal_trace_context.sql` → `maestro-store-postgres/src/main/resources/db/maestro/store/`; `maestro-lock-postgres/src/main/resources/db/migration/V100__maestro_lock_postgres.sql` → `.../db/maestro/lock/`; `maestro-messaging-postgres/src/main/resources/db/migration/V200__maestro_messaging_postgres.sql`, `V201__redelivery_dead_letter.sql` → `.../db/maestro/messaging/`. File contents untouched (the V1/V200 header comments that still say `classpath:db/migration` stay — editing a migration is forbidden; the new location is documented in `docs/configuration.md`).
- Create: `maestro-store-postgres/src/main/resources/META-INF/maestro/schema-locations` (content: `classpath:db/maestro/store`), `maestro-lock-postgres/src/main/resources/META-INF/maestro/schema-locations` (`classpath:db/maestro/lock`), `maestro-messaging-postgres/src/main/resources/META-INF/maestro/schema-locations` (`classpath:db/maestro/messaging`)
- Create: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/schema/SchemaMode.java`, `.../schema/MaestroSchemaInitializer.java`, `.../schema/package-info.java`
- Modify: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/config/PostgresStoreAutoConfiguration.java:68-78`
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java` (`StoreProperties` gains `schema`)
- Modify test supports: `maestro-store-postgres/src/test/.../PostgresTestSupport.java:58-60`, `maestro-lock-postgres/src/test/.../PostgresLockTestSupport.java:53-55`, `maestro-messaging-postgres/src/test/.../PostgresMessagingTestSupport.java:57-59`, `maestro-integration-tests/src/test/.../support/PostgresIntegrationSupport.java:87-93`; every existing `ApplicationContextRunner` test in `maestro-store-postgres/src/test/.../config/` gains `maestro.store.schema.mode=none` (they use a mocked `DataSource`)
- Rewrite: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/schema/MaestroMigrationsCoexistIT.java`; create `maestro-integration-tests/src/test/resources/db/migration/V1__init.sql`
- Test: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/schema/MaestroSchemaInitializerTest.java` (create)
- Modify builds: remove `runtimeOnly(libs.spring.boot.starter.flyway)` and fix the comment in `maestro-samples/sample-order-service/build.gradle.kts:14-20`, `sample-payment-gateway/build.gradle.kts:14-20`, `sample-postgres-only/build.gradle.kts:13-18`, `sample-loan-origination/{loan-application,verification-gateway,underwriting}-service/build.gradle.kts` (`:41`, `:39`, `:39`)
- Modify docs: `docs/getting-started.md:40-52` (dependencies), `:156` ("Maestro uses Flyway..."), `docs/configuration.md` (new "Schema management" subsection under Store Configuration; `:239` and `:286` sentences), `docs/operations.md` §10.6, `docs/maestro-architecture.md:121`, `docs/release-notes.md`

**Interfaces:**
- Consumes: Task 3's `StoreProperties(type)`.
- Produces:
  - `public enum SchemaMode { FLYWAY, VALIDATE, NONE }` (`io.b2mash.maestro.store.postgres.schema`).
  - `public final class MaestroSchemaInitializer` with `public MaestroSchemaInitializer(DataSource dataSource, SchemaMode mode, ClassLoader classLoader)`, `public void initialize()`, `public static List<String> discoverLocations(ClassLoader cl)` (reads every `META-INF/maestro/schema-locations` resource, one location per non-blank line, de-duplicated, sorted), `public static final String HISTORY_TABLE = "maestro_schema_history"`. `initialize()`: `NONE` → log INFO and return; otherwise `Flyway.configure().dataSource(ds).table(HISTORY_TABLE).locations(locations...).baselineOnMigrate(false).load()`; `FLYWAY` → `migrate()`; `VALIDATE` → `validate()` then if `info().pending().length > 0` throw `IllegalStateException("Maestro schema is behind: pending migrations %s. Apply them (maestro.store.schema.mode=flyway) or run the SQL under %s yourself." )` naming the pending versions and locations.
  - `MaestroProperties.StoreProperties(@DefaultValue("postgres") String type, @DefaultValue SchemaProperties schema)`, `record SchemaProperties(@DefaultValue("flyway") String mode)` with `defaults()`. The property is `maestro.store.schema.mode` = `flyway | validate | none`, default `flyway`.
  - `PostgresStoreAutoConfiguration`: `@Bean MaestroSchemaInitializer maestroSchemaInitializer(DataSource ds, Environment env)` reads `env.getProperty("maestro.store.schema.mode", SchemaMode.class, SchemaMode.FLYWAY)` (Environment, not `MaestroProperties`, for the same ordering reason as `:72-75`), calls `initialize()` inside the bean method; `postgresWorkflowStore(DataSource, ObjectMapper, Environment, MaestroSchemaInitializer initializer)` takes it as a parameter so the schema exists before the store bean.
  - Dependency statement: `maestro-store-postgres` keeps `implementation(libs.flyway.core)` + `runtimeOnly(libs.flyway.postgres)` (`build.gradle.kts:16-17`); both are already transitive **runtime** dependencies of every consumer (Gradle `implementation`/`runtimeOnly` publish as Maven `runtime` scope). Hosts need `spring-boot-starter-jdbc` (for the `DataSource`) and do **not** need `spring-boot-starter-flyway` for Maestro.

**Decision record:** the admin app (`maestro-admin`) runs its own Flyway at `classpath:db/migration/admin` against its own database (`maestro-admin/src/main/resources/application.yml:10-12`); it *is* the host application there and shares nothing with service databases, so it does not get the initializer. No change.

- [ ] **Step 1: Write the failing module test**

`MaestroSchemaInitializerTest` (Testcontainers Postgres like `PostgresTestSupport`, but a **fresh database per test** via `postgres.withDatabaseName` + `CREATE DATABASE` or by truncating `maestro_schema_history` and dropping `maestro_*` tables in `@BeforeEach`):

```java
@Test void discoverLocationsFindsThisModule() {
    assertThat(MaestroSchemaInitializer.discoverLocations(getClass().getClassLoader()))
        .contains("classpath:db/maestro/store");
}
@Test void flywayModeCreatesTablesInMaestrosOwnHistory() throws SQLException {
    new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, cl).initialize();
    assertThat(tables()).contains("maestro_workflow_instance", "maestro_schema_history").doesNotContain("flyway_schema_history");
    assertThat(versions("maestro_schema_history")).containsExactly("1", "2", "3", "4");
}
@Test void validateModeFailsOnAnEmptyDatabaseNamingPendingVersions() {
    assertThatThrownBy(() -> new MaestroSchemaInitializer(ds, SchemaMode.VALIDATE, cl).initialize())
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("pending").hasMessageContaining("1");
}
@Test void validateModePassesOnceMigrated() { /* FLYWAY then VALIDATE: no exception */ }
@Test void noneModeTouchesNothing() throws SQLException {
    new MaestroSchemaInitializer(ds, SchemaMode.NONE, cl).initialize();
    assertThat(tables()).isEmpty();
}
```

Also add to `PostgresStoreAutoConfigurationTest`: `schemaModeBindsFromEnvironment` (property `maestro.store.schema.mode=none` → `MaestroSchemaInitializer` bean present; a `maestro.store.schema.mode=bogus` → context fails with a conversion error).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-store-postgres:test --tests '*MaestroSchemaInitializerTest*' --console=plain`
Expected: compilation failure — `io.b2mash.maestro.store.postgres.schema` does not exist.

- [ ] **Step 3: Implement**

Move the migrations, add the three `schema-locations` marker files, write `SchemaMode`, `MaestroSchemaInitializer` (Javadoc: why its own history table, what each mode does, that the host's Flyway is untouched), wire the auto-configuration and the `StoreProperties.schema` record (follow the "exactly one canonical constructor" rule at `MaestroProperties.java:185-196`). Update the four test-support `locations("classpath:db/migration")` calls to the module's new location (store: `classpath:db/maestro/store`; lock: `classpath:db/maestro/lock`; messaging: `classpath:db/maestro/messaging`); `PostgresIntegrationSupport.migrate()` becomes `new MaestroSchemaInitializer(newDataSource(), SchemaMode.FLYWAY, PostgresIntegrationSupport.class.getClassLoader()).initialize()`.

- [ ] **Step 4: Run module suites**

Run: `./gradlew :maestro-store-postgres:test :maestro-lock-postgres:test :maestro-messaging-postgres:test --console=plain`
Expected: BUILD SUCCESSFUL (existing contract suites still find their tables via the new locations).

- [ ] **Step 5: Rewrite the coexistence IT (Review Focus 2)**

`maestro-integration-tests/src/test/resources/db/migration/V1__init.sql`: `CREATE TABLE host_customer (id UUID PRIMARY KEY, name VARCHAR(255) NOT NULL);`. Rewrite `MaestroMigrationsCoexistIT` (keep its own `@Container`, fresh database):

```java
@Test void hostFlywayAndMaestroHistoryCoexistWithoutVersionCollision() throws SQLException {
    // Host: Spring Boot's default location + default history table
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    assertEquals(List.of("1"), versions("flyway_schema_history"), "classpath:db/migration contains only the host's V1 now");
    assertTrue(tables().contains("host_customer"));

    new MaestroSchemaInitializer(ds, SchemaMode.FLYWAY, getClass().getClassLoader()).initialize();

    assertEquals(List.of("1", "2", "3", "4", "100", "200", "201"), versions("maestro_schema_history"));
    assertEquals(List.of("1"), versions("flyway_schema_history"), "Maestro wrote nothing into the host's history");
    assertTrue(tables().containsAll(List.of("maestro_workflow_instance", "maestro_distributed_lock", "maestro_signal_queue")));
}
@Test void maestroMigrationVersionsAreUniqueAcrossModules() { /* Flyway.configure().locations(discoverLocations(cl)).load().info().all() has no duplicate versions */ }
```
Keep `v4AddsTheNullableSignalTraceContextColumn` with the initializer in place of the raw Flyway call. (Task 6 appends `"202"` to the expected list.)

Run: `./gradlew :maestro-integration-tests:test --tests '*MaestroMigrationsCoexistIT*' --console=plain` → 3 tests passed.

- [ ] **Step 6: Builds and docs**

Remove `spring-boot-starter-flyway` from the six sample builds; replace each comment with `// Spring Boot 4 modular auto-configuration: JDBC (DataSource) lives in its own starter and must be declared explicitly. Maestro applies its own schema (maestro_schema_history) via maestro-store-postgres; no Flyway starter needed.` Docs:
- `docs/getting-started.md:40-52`: add `implementation("org.springframework.boot:spring-boot-starter-jdbc")` under `// Spring Boot`; replace `:156` with (verbatim): *"Maestro creates and upgrades its own `maestro_*` tables at startup with an embedded Flyway run, recorded in its own history table `maestro_schema_history`. Your application's Flyway (if any) and its `flyway_schema_history` are untouched — a `V1__init.sql` of your own does not collide with Maestro's `V1`. Set `maestro.store.schema.mode: validate` to fail startup instead when migrations are pending, or `none` to apply the SQL yourself."*
- `docs/configuration.md` Store Configuration: add rows for `maestro.store.schema.mode` (`String`, `"flyway"`, "`flyway` applies pending Maestro migrations; `validate` fails startup if any are pending; `none` skips — you own the DDL") and a `### Schema management` subsection naming the history table, the three classpath locations and bands, and that the host's `spring.flyway.*` is unaffected. Fix `:239` ("Additional Flyway migrations create the queue tables ...") and `:286` to say the tables are created by Maestro's schema initializer. Add an **Upgrading an existing database** note: a database migrated by an earlier Maestro build has Maestro's rows in `flyway_schema_history`; drop the `maestro_*` tables and those rows (pre-1.0, no data-preserving path), or set `maestro.store.schema.mode=none`.
- `docs/operations.md` §10.6: add the same history-table paragraph. `docs/maestro-architecture.md:121`: "Postgres implementation + Flyway 11 migrations (own history table)".
- Release notes `### Changed — Maestro owns its schema migrations` (history table, locations, new property, samples no longer need the Flyway starter, the upgrade note).

- [ ] **Step 7: Whole-PR gate and commit**

Run: `./gradlew build --console=plain` → BUILD SUCCESSFUL (Docker required).

```bash
git add -A maestro-store-postgres maestro-lock-postgres maestro-messaging-postgres maestro-spring-boot-starter maestro-integration-tests maestro-samples docs
git commit -m "fix(store): Maestro-owned Flyway history (maestro_schema_history) and module-specific migration locations (F5, F9)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Implement per-task-queue concurrency (`maestro.worker.*`, F8 / Issue 25)

**Files:**
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/TaskQueueLimits.java`, `.../engine/TaskQueueGovernor.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:317-371` (primary ctor + new overload), `:1410-1421` and `:1533-1543` (`executeWorkflow` acquire/release), `:1360-1362` (`DefaultWorkflowOperations` construction), `:360-362` (`SignalManager` construction)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java:110-121`, `:140-...` (ctors), `:346` (park site)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/SignalManager.java:124-150` (ctors), `:384` (park site — if Plan 04 moved it, wrap wherever `parkingLot.parkWithTimeout` is now called)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityProxyFactory.java:142-154` (new full overload), `.../ActivityInvocationHandler.java:359-420` (`executeLive`)
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroAutoConfiguration.java:71-105`, `.../proxy/ActivityStubBeanPostProcessor.java:96-120` (resolve the governor), `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java:316-342` (Javadoc only)
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/TestWorkflowEnvironment.java:82-92` (pass `UNBOUNDED`), `maestro-integration-tests/.../support/MaestroEngineHarness.java` builder (`taskQueueLimits(Map)`)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/TaskQueueGovernorTest.java`, `.../WorkflowExecutorConcurrencyTest.java` (create); `maestro-spring-boot-starter/src/test/.../MaestroAutoConfigurationConfigSeamsTest.java` (add); `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/TaskQueueConcurrencyIT.java` (create)
- Modify docs: `docs/configuration.md:305-345`, `docs/open-issues.md:2155-2162`, `docs/release-notes.md`

**Interfaces:**
- Consumes: Task 1's executor signatures.
- Produces:
  - `public record TaskQueueLimits(int workflowConcurrency, int activityConcurrency)` — both `>= 1` else `IllegalArgumentException`.
  - `public final class TaskQueueGovernor` (framework-free, thread-safe): `TaskQueueGovernor(Map<String, TaskQueueLimits> limits)`; `public static final TaskQueueGovernor UNBOUNDED = new TaskQueueGovernor(Map.of())`; `void acquireWorkflowSlot(String taskQueue, String workflowId)` (blocks; records `workflowId → taskQueue`; converts `InterruptedException` into `ExecutorShutdownException("interrupted while waiting for a workflow slot on queue '" + taskQueue + "'")`); `void releaseWorkflowSlot(String workflowId)` (no-op when not held — idempotent, safe in `finally`); `<T> T parked(String workflowId, Callable<T> park) throws Exception` (releases the slot, runs `park`, reacquires only when `park` returned normally or threw `TimeoutException`; an `Error` propagates without reacquire); `void acquireActivitySlot(String taskQueue)`, `void releaseActivitySlot(String taskQueue)`; `OptionalInt availableWorkflowSlots(String taskQueue)`, `OptionalInt availableActivitySlots(String taskQueue)` (empty = unbounded). Unconfigured queues are unbounded (every method a no-op).
  - `WorkflowExecutor` 13-argument constructor = the 12-argument one at `:317-329` plus trailing `Map<String, TaskQueueLimits> taskQueueLimits`; the 12-argument one delegates with `Map.of()`. `public TaskQueueGovernor taskQueueGovernor()`.
  - `ActivityProxyFactory.createProxy(..., EngineObserver observer, TaskQueueGovernor governor)`; the 11-argument overload at `:142-154` delegates with `UNBOUNDED`.
  - `DefaultWorkflowOperations` and `SignalManager` full constructors gain a trailing `TaskQueueGovernor`; shorter overloads pass `UNBOUNDED`.
  - Starter: `MaestroAutoConfiguration.maestroWorkflowExecutor` builds `Map<String, TaskQueueLimits>` from `properties.getWorker().taskQueues()`; a duplicate `name` → `IllegalStateException("maestro.worker.task-queues names a queue twice: 'orders'")`.

- [ ] **Step 1: Write the failing core tests**

`TaskQueueGovernorTest`:
```java
@Test void unconfiguredQueueIsUnbounded() { assertTrue(UNBOUNDED.availableWorkflowSlots("x").isEmpty()); UNBOUNDED.acquireWorkflowSlot("x", "wf"); /* returns immediately */ }
@Test void releaseIsIdempotent() { g.acquireWorkflowSlot("q", "wf"); g.releaseWorkflowSlot("wf"); g.releaseWorkflowSlot("wf"); assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("q")); }
@Test void parkedReleasesThenReacquires() throws Exception {
    var g = new TaskQueueGovernor(Map.of("q", new TaskQueueLimits(1, 1)));
    g.acquireWorkflowSlot("q", "wf");
    var seenInside = g.parked("wf", () -> g.availableWorkflowSlots("q"));
    assertEquals(OptionalInt.of(1), seenInside); assertEquals(OptionalInt.of(0), g.availableWorkflowSlots("q"));
}
@Test void parkedDoesNotReacquireAfterAnError() { /* Callable throws ExecutorShutdownException → propagates; slot stays released */ }
```
`WorkflowExecutorConcurrencyTest` (executor via the 13-arg ctor, `Map.of("orders", new TaskQueueLimits(2, 1))`; a `BlockingActivities` fake whose `work()` counts `started` and awaits a latch):
```java
@Test void workflowConcurrencyBoundsSimultaneousRuns() {
    start 3 workflows on queue "orders"; await().until(() -> started.get() == 2);
    await().during(Duration.ofMillis(500)).until(() -> started.get() == 2);      // the third waits for a slot
    latch.countDown(); await().until(() -> started.get() == 3);
}
@Test void activityConcurrencyBoundsLiveActivities() { /* limits (10, 1): two workflows each call work() → started stays 1 until the latch opens */ }
@Test void parkedWorkflowReleasesItsSlot() {                                 // Review Focus 4
    limits (1, 10). Workflow A: work(); awaitSignal("go"). Workflow B: work().
    start A; await A parked (executor.parkedCount()==1); start B; await started==2  // B got A's slot
    deliverSignal(A, "go"); A must finish after B completes: assert A's status becomes COMPLETED
}
@Test void unconfiguredQueueIsUnboundedEndToEnd() { /* 5 workflows on queue "other" all start */ }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-core:test --tests '*TaskQueueGovernor*' --tests '*WorkflowExecutorConcurrencyTest*' --console=plain`
Expected: compilation failure — `TaskQueueGovernor` does not exist.

- [ ] **Step 3: Implement core**

Write `TaskQueueLimits`, `TaskQueueGovernor` (one `Semaphore` pair per configured queue, `ConcurrentHashMap<String, String> heldBy` for workflow slots; Javadoc states semantics: slot held from run start to park/terminal; reacquired on wake; activity slot held only around a live execution, never on replay). Executor: new ctor and accessor; in `executeWorkflow` the first statement inside `try` is `taskQueueGovernor.acquireWorkflowSlot(ctx.taskQueue(), ctx.workflowId())` and the `finally` at `:1533` starts with `taskQueueGovernor.releaseWorkflowSlot(ctx.workflowId())`; pass the governor into `DefaultWorkflowOperations` (`:1360`) and `SignalManager` (`:360`). Wrap the two park sites: `governor.parked(ctx.workflowId(), () -> { parkingLot.parkWithTimeout(parkKey, chunk); return null; })` — keep the surrounding `catch (TimeoutException)` logic by letting `parked` rethrow it. `ActivityInvocationHandler.executeLive` (`:359`): wrap the body from `acquireLock` to the persisted result in `governor.acquireActivitySlot(ctx.taskQueue())` / `finally releaseActivitySlot`. Thread the governor through `ActivityProxyFactory.createProxy` to the handler.

- [ ] **Step 4: Run core tests**

Run: `./gradlew :maestro-core:test --console=plain` → BUILD SUCCESSFUL, the two new classes pass (8 tests).

- [ ] **Step 5: Starter binding test, then wire**

Add to `MaestroAutoConfigurationConfigSeamsTest`:
```java
@Test void workerConcurrencyReachesTheGovernor() {
    runner.withPropertyValues("maestro.worker.task-queues[0].name=orders",
                              "maestro.worker.task-queues[0].concurrency=2",
                              "maestro.worker.task-queues[0].activity-concurrency=3")
          .run(ctx -> { var g = ctx.getBean(WorkflowExecutor.class).taskQueueGovernor();
                        assertEquals(OptionalInt.of(2), g.availableWorkflowSlots("orders"));
                        assertEquals(OptionalInt.of(3), g.availableActivitySlots("orders"));
                        assertTrue(g.availableWorkflowSlots("unconfigured").isEmpty()); });
}
@Test void duplicateQueueNameFailsStartup() { /* two entries named "orders" → hasFailed, message contains "names a queue twice" */ }
```
Run (expect compile failure), then implement in `MaestroAutoConfiguration.maestroWorkflowExecutor` and make `ActivityStubBeanPostProcessor.ensureDependenciesResolved` (`:96-120`) fetch `ctx.getBean(WorkflowExecutor.class).taskQueueGovernor()` and pass it to `createProxy`. `TestWorkflowEnvironment` and `MaestroEngineHarness` pass `UNBOUNDED` unless configured (harness builder gets `taskQueueLimits(Map<String, TaskQueueLimits>)`).

Run: `./gradlew :maestro-spring-boot-starter:test :maestro-test:test --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 6: Real-Postgres pin**

`TaskQueueConcurrencyIT extends PostgresIntegrationSupport` (harness with `taskQueueLimits(Map.of("default", new TaskQueueLimits(1, 10)))`, a latching `ChainActivities`): start two `ChainWorkflow`s; `await().during(500ms)` that `recorder.count("stepOne") == 1`; release; both reach `COMPLETED`; event logs of both are complete (`4:WORKFLOW_COMPLETED`).

Run: `./gradlew :maestro-integration-tests:test --tests '*TaskQueueConcurrencyIT*' --console=plain` → 1 passed.

- [ ] **Step 7: Docs, ledger, commit**

`docs/configuration.md:305-345`: delete the "Not yet implemented" callout (`:307-317`); rewrite the two semantic sentences after the table (verbatim): *"`concurrency` caps how many workflow runs on this queue execute at once on this node; a run holds its slot from start until it parks on `sleep()`/`awaitSignal()` or reaches a terminal status, and reacquires one before resuming. `activity-concurrency` caps live activity executions per queue on this node; replayed (memoized) steps never take a slot. Queues not listed here are unbounded. Limits are per node — three nodes with `concurrency: 10` run up to 30 at once."* Also state that `taskQueue` on `@DurableWorkflow` names this bucket. In `docs/open-issues.md:2155-2162` replace the `> **Open.**` callout with `> **Resolved.** Implemented as per-node `Semaphore`s in `TaskQueueGovernor` (maestro-core), bound from `maestro.worker.task-queues` by `MaestroAutoConfiguration`. Commit <hash>. Pinned by `WorkflowExecutorConcurrencyTest`, `MaestroAutoConfigurationConfigSeamsTest.workerConcurrencyReachesTheGovernor`, `TaskQueueConcurrencyIT`.` Release notes `### Added — maestro.worker.task-queues[].concurrency / activity-concurrency now bound execution`.

```bash
git add maestro-core maestro-spring-boot-starter maestro-test maestro-integration-tests docs/configuration.md docs/open-issues.md docs/release-notes.md
git commit -m "feat(core): per-task-queue workflow and activity concurrency via TaskQueueGovernor (F8, Issue 25)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Delete the task-dispatch channel and the unread lifecycle queue (F8 topics, F10)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowMessaging.java:5-8` (Javadoc), delete `publishTask` `:35-43`, `subscribe` `:68-84`; delete `maestro-core/src/main/java/io/b2mash/maestro/core/spi/TaskMessage.java`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/GatedWorkflowMessaging.java:82-83`, `:100-101`; `maestro-test/src/main/java/io/b2mash/maestro/test/InMemoryWorkflowMessaging.java:15`, `:34-37`, `:49-52`
- Modify: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/KafkaWorkflowMessaging.java:36`, `:128-133`, `:157-176`, `:287-291`; `KafkaMessagingConfig.java:42` (+ Javadoc `:12`, `:27`); `config/KafkaMessagingAutoConfiguration.java:226` and a new fail-fast guard
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java:285-294` (`TopicsProperties` loses `tasks`)
- Modify: `maestro-messaging-postgres/src/main/java/io/b2mash/maestro/messaging/postgres/PostgresWorkflowMessaging.java:28-29`, `:69`, `:126-154`, `:188-218`, `:224-...`, `:567-...`, `:599-...`, `:628`; `PostgresMessageCleaner.java:46-50`, `:58-60`, `:89`
- Create: `maestro-messaging-postgres/src/main/resources/db/maestro/messaging/V202__drop_task_and_lifecycle_queues.sql`
- Modify tests: every `WorkflowMessaging` test fake overriding `publishTask`/`subscribe` (grep `publishTask(String taskQueue` under `maestro-core/src/test`, `maestro-spring-boot-starter/src/test`); `maestro-messaging-kafka/src/test/.../KafkaWorkflowMessagingTest.java:82-151`, `:285-308`, `:385-389`; `KafkaTracePropagationContractTest.java:135-145`, `:288-299`; `maestro-messaging-postgres/src/test/.../PostgresWorkflowMessagingTest.java:58-114`, `:171-174`, `:256-262`; `PostgresMessagingTestSupport.java:102`; `maestro-integration-tests/.../support/PostgresIntegrationSupport.java:60-67`; `MaestroMigrationsCoexistIT` expected versions (+ `"202"`, table assertion `maestro_signal_queue`)
- Modify docs/config: `docs/configuration.md:83`, `:153-155`, `:239`, `:522`, `:599-605`, `:709-711`; `docs/getting-started.md:103-112`, `:133`, `:277` (annotation table `taskQueue` row); `docs/cross-service.md:188`, `:202`, `:333`, `:357`, `:372`, `:471`; `docs/maestro-architecture.md:460`; `docs/observability.md:279`; `docs/admin.md:139`; `maestro-samples/README.md:161-162`; `docker-compose.yml:95-96`; `demo/docker-compose.yml:124-126`, `:156-158`; `maestro-samples/sample-loan-origination/docker-compose.yml:95-97`, `:126-128`; `demo/scripts/lib/jvm-env.sh:75`; `topics.tasks` lines in `maestro-samples/sample-order-service/src/main/resources/application.yml`, `sample-payment-gateway/.../application.yml`, the three `sample-loan-origination/*/src/main/resources/application.yml`; `docs/release-notes.md`

**Interfaces:**
- Consumes: Task 4's migration location `db/maestro/messaging`.
- Produces: `WorkflowMessaging` has exactly three methods: `publishSignal`, `publishLifecycleEvent`, `subscribeSignals`. `TopicsProperties(@Nullable String signals, @DefaultValue("maestro.admin.events") String adminEvents)`. `KafkaMessagingConfig` loses `tasksTopic`. `KafkaMessagingAutoConfiguration` throws `IllegalStateException` when `env.containsProperty("maestro.messaging.topics.tasks")`: `"maestro.messaging.topics.tasks has been removed: Maestro no longer dispatches work over a task topic (workflows start in-process). Delete the property and the maestro.tasks.* topics."`. `PostgresWorkflowMessaging.publishLifecycleEvent` is a no-op that logs once (`AtomicBoolean`) at WARN (verbatim): `"Lifecycle events are not supported on the Postgres messaging transport — the admin dashboard consumes Kafka only. Set maestro.admin.events.enabled=false to silence this warning."`

**Verification already done (do not redo):** `grep -rn "publishTask\|\.subscribe(" --include='*.java' . | grep -v src/test` shows the only production callers are the SPI implementations themselves and `GatedWorkflowMessaging`'s delegation; `SignalSubscriptionRunner.java:90` calls only `subscribeSignals`. Nothing reads `maestro_lifecycle_event_queue` (`AdminEventConsumer` is a Kafka container; `grep -rn lifecycle_event_queue` hits only the writer, the cleaner, migrations and tests). `maestro_task_queue` is written and read only by the Postgres `publishTask`/`subscribe` pair being deleted, so it goes in the same migration.

- [ ] **Step 1: Write the failing tests**

- `maestro-messaging-postgres/src/test/.../PostgresWorkflowMessagingTest`: replace the lifecycle assertions at `:171-174` with `publishLifecycleEventIsANoOpThatWarnsOnce` — call twice, assert no `maestro_lifecycle_event_queue` table exists (`information_schema.tables` count 0) and that a captured SLF4J appender (or a `ListAppender` on the class logger) saw exactly one WARN containing "Kafka only".
- `maestro-messaging-kafka/src/test/.../config/KafkaMessagingAutoConfigurationTest` (or the existing admin-topic alias test class): `tasksTopicPropertyFailsStartup` with `maestro.messaging.topics.tasks=maestro.tasks.orders` → `hasFailed`, root cause message contains "has been removed".
- `MaestroMigrationsCoexistIT`: expected versions `List.of("1","2","3","4","100","200","201","202")`; tables must not contain `maestro_task_queue` or `maestro_lifecycle_event_queue`.
- Core: an ArchUnit-free reflection pin in `maestro-core/src/test/.../spi/WorkflowMessagingSurfaceTest.java`: `assertEquals(Set.of("publishSignal", "publishLifecycleEvent", "subscribeSignals"), Arrays.stream(WorkflowMessaging.class.getDeclaredMethods()).map(Method::getName).collect(toSet()))`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-core:test --tests '*WorkflowMessagingSurfaceTest*' --console=plain` → FAIL (set contains `publishTask`, `subscribe`).

- [ ] **Step 3: Implement the deletions**

Delete the two SPI methods and `TaskMessage`; let the compiler find every override and call (core fakes, test kit, Kafka, Postgres, `GatedWorkflowMessaging`). Kafka: remove `publishTask`, `subscribe`, `resolveTaskTopic`, `tasksTopic`, the `:36` Javadoc bullet; add the Environment guard to `KafkaMessagingAutoConfiguration`. Postgres: remove task SQL, `TASK_CHANNEL`, `listDeadLetterTasks`, `replayTask`, the `replay` qualifier branch at `:628`; make `publishLifecycleEvent` the warn-once no-op; cleaner deletes only `maestro_signal_queue` rows. Migration `V202__drop_task_and_lifecycle_queues.sql`:
```sql
-- Maestro no longer dispatches work over a task channel, and lifecycle events
-- are Kafka-only (nothing ever read this queue). Both tables are dropped.
DROP TABLE IF EXISTS maestro_task_queue;
DROP TABLE IF EXISTS maestro_lifecycle_event_queue;
```
Update the truncate lists in `PostgresMessagingTestSupport.java:102` and `PostgresIntegrationSupport.java:60-67`. Delete the Kafka task tests and the task sections of the trace-propagation test; keep the signal equivalents.

- [ ] **Step 4: Run all affected suites**

Run: `./gradlew :maestro-core:test :maestro-test:test :maestro-spring-boot-starter:test :maestro-messaging-kafka:test :maestro-messaging-postgres:test :maestro-integration-tests:test --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Docs, compose and config**

Remove every `maestro.tasks.*` topic creation line and `topics.tasks:` property listed in **Files** (compose files, sample YAMLs, getting-started `:103-112` task-topic block and `:133`, samples README, cross-service, configuration `:83`, `:153-155`, `:522`, `:601-605`, `:709-711`, architecture `:460`, observability `:279`, `jvm-env.sh:75` comment → "`maestro.signals.*`"). `docs/configuration.md:239`: "creates the queue table (`maestro_signal_queue`)". `docs/getting-started.md` annotation table (`:277`) `taskQueue` cell: *"names the concurrency bucket configured under `maestro.worker.task-queues`."* `docs/admin.md:139` (verbatim): *"> **Note:** Lifecycle events reach the dashboard over **Kafka only**. With Postgres messaging (`maestro.messaging.type: postgres`) no lifecycle events are published — `publishLifecycleEvent` is a no-op that logs one WARN. Set `maestro.admin.events.enabled: false` in Postgres-only services to silence it; the dashboard cannot observe those services."* Release notes `### Removed`: `WorkflowMessaging.publishTask/subscribe`, `TaskMessage`, `maestro.messaging.topics.tasks` (fails startup if set), the `maestro.tasks.*` topics and their `.DLT`s, `maestro_task_queue` and `maestro_lifecycle_event_queue` (migration `V202`); and `### Changed` for the Postgres-transport lifecycle no-op.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(messaging): delete the unused task-dispatch channel and the unread Postgres lifecycle queue (F8, F10)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Complete `WorkflowHandle` and make `startAndWait` failures carry the cause (F11)

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/client/WorkflowHandle.java` (whole file), `MaestroClient.java:80-82`, `WorkflowStub.java:92-130`
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/exception/WorkflowExecutionException.java` (add constructor), `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:1727-1731`, `:2013`, `:2019` (make the detail records public model types)
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/model/WorkflowFailureDetail.java`, `.../model/WorkflowTerminationDetail.java` — **unless Plan 02 already introduced public equivalents; then reuse those and skip creation**
- Test: `maestro-spring-boot-starter/src/test/java/io/b2mash/maestro/spring/client/MaestroClientTest.java` (add nested `HandleTests`)
- Modify docs: `docs/concepts.md:520-580` (MaestroClient API Reference), `docs/getting-started.md` Step 8 controller (`status` endpoint gets a durable fallback comment)

**Interfaces:**
- Consumes: Plan 02's exception-reconstruction factory (its Interfaces block names the type that turns `(exceptionType, message)` into a `Throwable`; call that). Task 1's query contract.
- Produces:
  - `public final class WorkflowHandle<R>` with `void signal(String, @Nullable Object)`, `<Q> Q query(String, Class<Q>)`, `<Q> Q query(String, @Nullable Object, Class<Q>)`, `String workflowId()`, **new** `WorkflowStatus status()` (throws `WorkflowNotFoundException`), `Optional<R> result()` (present only when `COMPLETED` with non-null output, deserialised to `R`; empty otherwise; throws `WorkflowNotFoundException` when no instance), `WorkflowExecutor.TerminateOutcome terminate(@Nullable String reason)`.
  - `MaestroClient.getWorkflow(String workflowId)` → `WorkflowHandle<Object>`; **new** `<R> WorkflowHandle<R> getWorkflow(String workflowId, Class<R> resultType)`.
  - `public record WorkflowFailureDetail(String exceptionType, @Nullable String message)` and `public record WorkflowTerminationDetail(@Nullable String reason, String terminatedBy)` in `io.b2mash.maestro.core.model`, written by the executor exactly where `ErrorDetail`/`TerminationDetail` were (`:1727`, `:2013`, `:2019`).
  - `WorkflowExecutionException(String workflowId, String message, @Nullable Throwable cause)`.
  - `WorkflowStub.startAndWait` on `FAILED`: `throw new WorkflowExecutionException(id, "Workflow '%s' failed: %s: %s".formatted(id, detail.exceptionType(), detail.message()), reconstructed)` where `reconstructed` comes from Plan 02's factory (null if the output payload is missing). On `TERMINATED`: message `"Workflow '%s' was terminated by %s%s"` with `": " + reason` appended when present, cause `null`.

- [ ] **Step 1: Write the failing tests**

In `MaestroClientTest` add fixtures `FailingWorkflow` (`throw new IllegalStateException("boom")`), `ParkingWorkflow` (`awaitSignal("go", String.class, Duration.ofMinutes(1))` then returns its input), and tests:
```java
@Test void statusReportsRunningThenCompleted() { start ParkingWorkflow "h-1"; await WAITING_SIGNAL via client.getWorkflow("h-1").status(); signal; await COMPLETED }
@Test void resultIsEmptyWhileRunningAndPresentAfterCompletion() {
    var h = client.getWorkflow("h-2", String.class); ... assertTrue(h.result().isEmpty()); signal; await; assertEquals(Optional.of("in"), h.result()); }
@Test void terminateMovesTheWorkflowToTerminated() { assertEquals(TerminateOutcome.TERMINATED, client.getWorkflow("h-3").terminate("ops")); await TERMINATED }
@Test void getWorkflowOnUnknownIdThrowsOnStatus() { assertThrows(WorkflowNotFoundException.class, () -> client.getWorkflow("nope").status()); }
@Test void startAndWaitFailureCarriesTheWorkflowsExceptionMessageAndCause() {
    var ex = assertThrows(WorkflowExecutionException.class, () -> client.newWorkflow(FailingWorkflow.class, options("f-1")).startAndWait(null, Duration.ofSeconds(10), Void.class));
    assertTrue(ex.getMessage().contains("IllegalStateException: boom"), ex.getMessage());
    assertInstanceOf(IllegalStateException.class, ex.getCause()); assertEquals("boom", ex.getCause().getMessage());
}
@Test void startAndWaitOnTerminatedWorkflowCarriesTheReason() {                  // Review Focus 5
    // start ParkingWorkflow on another thread via startAndWait; once parked, client.getWorkflow(id).terminate("maintenance window");
    // the startAndWait thread's exception message contains "was terminated" and "maintenance window"
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-spring-boot-starter:test --tests '*MaestroClientTest*' --console=plain` → compilation failure (`status()`, `result()`, `terminate()` undefined).

- [ ] **Step 3: Implement**

Promote the two private detail records to public model records (or reuse Plan 02's), add the `WorkflowExecutionException` constructor, rewrite `WorkflowHandle` as a generic final class constructed with `(WorkflowExecutor, WorkflowStore, PayloadSerializer, String workflowId, Class<R> resultType)`, add the typed `MaestroClient.getWorkflow` overload, and replace `WorkflowStub.java:108-113` with the detail-reading branch described in Interfaces. Javadoc on `status()`/`result()`: they read the store, so they work from any node (unlike `query`).

- [ ] **Step 4: Run starter and core suites**

Run: `./gradlew :maestro-core:test :maestro-spring-boot-starter:test --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 5: Docs, commit**

`docs/concepts.md` MaestroClient API Reference: after "Querying workflow state" add `### Status, result and termination` with a three-line snippet (`status()`, `getWorkflow(id, OrderResult.class).result()`, `terminate("reason")`) and the sentence *"These read and write the store, so they work from any node; only `query` requires the owning node."* Update the `startAndWait` sentence at `:556` to say the exception message names the failing exception and the cause is the reconstructed activity/workflow exception. Release notes `### Added — WorkflowHandle.status()/result()/terminate(); startAndWait failures carry the cause`.

```bash
git add maestro-core maestro-spring-boot-starter docs/concepts.md docs/release-notes.md
git commit -m "feat(client): WorkflowHandle status/result/terminate; startAndWait failures carry message and cause (F11)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Move sequence/replay mutators off the public `WorkflowContext` (F12)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/context/WorkflowContext.java:191-193` (`nextSequence`), `:223-225` (`setReplaying`), `:263-273` (`setSequence`), class Javadoc `:22-25`
- Create: `maestro-core/src/main/java/io/b2mash/maestro/core/context/WorkflowContextInternals.java`
- Modify callers: `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java:172`, `:204`, `:280-281`, `:329`, `:372`, `:465`; `.../engine/DefaultWorkflowOperations.java:200`, `:221`, `:227`, `:278`, `:463`, `:470`, `:551`, `:563`, `:645`, `:660`, `:672`, `:683`, `:695`, `:747`, `:758-759`; `.../engine/SignalManager.java:283`, `:313`; `.../engine/ActivityInvocationHandler.java:205`, `:239`; `.../engine/WorkflowExecutor.java:1905` (line numbers as of `main`; Plans 01–04 will have shifted them — grep `nextSequence()\|setSequence(\|setReplaying(` under `maestro-core/src/main`)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/context/WorkflowContextTest.java` (adjust), `maestro-core/src/test/java/io/b2mash/maestro/core/context/WorkflowContextSurfaceTest.java` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `public final class WorkflowContextInternals` (same package as `WorkflowContext`, private constructor) with `public static int nextSequence(WorkflowContext ctx)`, `public static void setSequence(WorkflowContext ctx, int sequence)`, `public static void setReplaying(WorkflowContext ctx, boolean replaying)`. The three `WorkflowContext` methods become package-private. `currentSequence()`, `isReplaying()` and all identity accessors stay public.

**Decision record:** callers live in `engine` and `saga`, so Java package-private alone cannot reach them, and `maestro-core` has no `module-info.java` to export selectively. The least-churn option that removes the mutators from `workflow.`-autocomplete is a separate, clearly named static accessor class in the `context` package; the methods it wraps are package-private. The class Javadoc states it is engine-internal and that calling it from workflow code corrupts replay. This is a deliberate, documented limitation.

- [ ] **Step 1: Write the failing surface test**

```java
class WorkflowContextSurfaceTest {
    @Test void engineMutatorsAreNotPublicOnWorkflowContext() {
        for (var name : List.of("nextSequence", "setSequence", "setReplaying")) {
            assertTrue(Arrays.stream(WorkflowContext.class.getMethods()).noneMatch(m -> m.getName().equals(name)),
                    name + " must not be a public method of WorkflowContext");
        }
    }
    @Test void internalsRoundTrip() {
        var ctx = new WorkflowContext(UUID.randomUUID(), "wf", UUID.randomUUID(), "t", "q", "svc", 0, true, null);
        assertEquals(1, WorkflowContextInternals.nextSequence(ctx));
        WorkflowContextInternals.setSequence(ctx, 41); assertEquals(42, WorkflowContextInternals.nextSequence(ctx));
        WorkflowContextInternals.setReplaying(ctx, false); assertFalse(ctx.isReplaying());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :maestro-core:test --tests '*WorkflowContextSurfaceTest*' --console=plain` → compilation failure (`WorkflowContextInternals` missing) / first test FAILS.

- [ ] **Step 3: Implement**

Create `WorkflowContextInternals`; make the three methods package-private (keep their Javadoc, drop the "do not call from workflow code" warnings since the public surface no longer shows them; put that warning on the internals class). Update every caller to the static form. Update `WorkflowContext` class Javadoc `:22-25` to reference `WorkflowContextInternals`.

- [ ] **Step 4: Run core and test-kit**

Run: `./gradlew :maestro-core:test :maestro-test:test --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

Release notes `### Changed — WorkflowContext.nextSequence/setSequence/setReplaying are no longer public` (moved to `WorkflowContextInternals`; third-party `WorkflowOperations` implementers use that class).

```bash
git add maestro-core docs/release-notes.md
git commit -m "refactor(core): move sequence/replay mutators to WorkflowContextInternals (F12)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: BOM module, README/CONTRIBUTING accuracy, competitive table, F3/F4 doc statements (F13 docs, §7)

**Files:**
- Create: `maestro-bom/build.gradle.kts`; modify `settings.gradle.kts:18-37` (add `"maestro-bom"`)
- Modify: `README.md:7` (pitch), `:13-23` ("Why Maestro?" table), `:47-60` (dependencies), `:212-227` (Modules table — add row), `:302-309` (Building from Source)
- Modify: `CONTRIBUTING.md:120-130`, `:272`
- Modify: `docs/getting-started.md:27-52` (build file — add the platform line), `docs/concepts.md:140-144` (Determinism Rule), `:192-212` (Awaiting a signal), `:280` (awaitSignal/`cancelTimer` sentence)
- Modify: `docs/release-notes.md` (`### Added — maestro-bom`), `docs/open-issues.md` §4 index paragraph (one sentence noting Issue 25 resolved in this cycle)

**Interfaces:**
- Consumes: Plan 02's Interfaces block for the stand-down reason added for replay drift (its `StandDownReason` constant name and the documented operator-visible behaviour) and Plan 04's Interfaces block for the durable `awaitSignal` timeout (timer row + `TestWorkflowEnvironment.advanceTime` firing it). Copy the exact names from those blocks.
- Produces: published platform `io.b2mash.maestro:maestro-bom` constraining `maestro-core`, `maestro-spring-boot-starter`, `maestro-store-jdbc`, `maestro-store-postgres`, `maestro-messaging-kafka`, `maestro-messaging-postgres`, `maestro-lock-valkey`, `maestro-lock-postgres`, `maestro-admin-client`, `maestro-test` at `project.version`.

- [ ] **Step 1: Create the BOM and verify its POM**

`maestro-bom/build.gradle.kts`: plugins `` `java-platform` ``, `` `maven-publish` ``, `signing`; `group = "io.b2mash.maestro"`, `version = property("version") as String`, `description = "Maestro BOM — aligned versions for every Maestro module"`; `dependencies { constraints { api(project(":maestro-core")); ... (the ten modules above) } }`; a `MavenPublication("mavenBom") { from(components["javaPlatform"]) }` with the same `pom { licenses/developers/scm }` block as `build-logic/src/main/kotlin/maestro.library-conventions.gradle.kts:15-42`; signing block identical to `:45-50`. Add `"maestro-bom"` to `settings.gradle.kts`. The module has no sources, so the root `verifyModuleTestCoverage` (`build.gradle.kts:37-39`, `hasMain` false) ignores it.

Run: `./gradlew :maestro-bom:generatePomFileForMavenBomPublication --console=plain && grep -c "<artifactId>maestro-" maestro-bom/build/publications/mavenBom/pom-default.xml`
Expected: BUILD SUCCESSFUL and `10`.

- [ ] **Step 2: README**

- Line 7 → (verbatim): `**Durable workflows for Spring Boot 4 teams on Postgres + Kafka — sagas, cross-service signals, free dashboard.** Add a starter to your microservice and get crash-recoverable workflows on the Postgres you already run; Kafka and Valkey are optional.`
- Replace the table at `:13-23` with (verbatim):

```markdown
| Solution | What it is | Trade-off against Maestro |
|---|---|---|
| **Temporal** | Durable execution with a separate server cluster (Postgres or MySQL; Cassandra and Elasticsearch optional; a single-binary dev server exists) | Most mature option, with determinism checking and a replayer; you run and upgrade a cluster next to your services |
| **DBOS Transact Java** | Embedded durable-execution library on Postgres only; Spring Boot 3.5 starter, Boot 4 support test-only; JDK 17; MIT (the Conductor UI is a paid service) | Closest alternative. More mature and polyglot; no saga compensation, no Kafka signal bridge, no free dashboard, Boot 4 is not its primary target |
| **JobRunr / JobRunr Pro** | Durable background jobs on your own database; very mature; Boot 4 support | Simplest durable jobs with a large install base; no replay/memoization model, no signals; durable multi-step workflows are a Pro (paid) feature |
| **Restate** | Single-binary durable-execution server with a Java/Kotlin SDK and Boot starter; BSL-licensed server | Low-latency K/V state without a database; your workflow state lives in Restate's server, not your Postgres |
| **Netflix Conductor** | Central orchestration server with a JSON workflow DSL (Elasticsearch optional) | Language-agnostic DSL and UI; a server to operate and workflows defined outside your code |
| **Camunda / Zeebe, Flowable** | BPMN engines (Flowable 8 embeds on any JDBC; Zeebe needs its own broker) | BPMN modelling, human tasks and history views; model-first rather than code-first |
| **Axon Framework** | Event-sourcing and CQRS framework | Event-sourcing model with a steep learning curve; Axon 5.1+ partly commercial |
| **Dapr Workflow** | Sidecar-based workflow runtime | Polyglot and Kubernetes-native; a sidecar per pod and state outside your database |
| **Maestro** | **Embedded Spring Boot 4 starter on your Postgres (+ optional Kafka, Valkey)** | **Sagas, cross-service signals with trace propagation, versioning, a test kit and a free dashboard — nothing new to deploy. Pre-1.0; see `docs/open-issues.md`.** |
```

- Dependencies `:47-60`: first line `implementation(platform("io.b2mash.maestro:maestro-bom:<version>"))`, then the four module lines without versions, then `implementation("org.springframework.boot:spring-boot-starter-jdbc")`; below the block: *"Replace `<version>` with the Maestro release you are adopting (`gradle.properties` on `main` holds the snapshot under development). Nothing is published to Maven Central yet; build and `publishToMavenLocal` from source."*
- Modules table: add `| \`maestro-bom\` | Gradle/Maven platform aligning all module versions | No |`.
- `:303-309` → `./gradlew build` (everything incl. real-backend suites; Docker), `./gradlew :maestro-core:test`, `./gradlew :maestro-integration-tests:test  # real Postgres/Kafka/Valkey (Docker)`, `./gradlew :maestro-integration-tests:e2eTest  # loan-origination E2E + chaos (Docker)`.
- Do **not** touch the "Durable Retries" feature bullet (`:36`) — Plan 03 owns that wording.

- [ ] **Step 3: CONTRIBUTING and getting-started**

`CONTRIBUTING.md:120-130`: replace both `integrationTest` commands with `./gradlew :maestro-integration-tests:test` (all) and `./gradlew :maestro-store-postgres:test` (one module's Testcontainers suite; `test` is the only task — there is no `integrationTest` task); `:272` likewise. `docs/getting-started.md:40-52`: add `implementation(platform("io.b2mash.maestro:maestro-bom:<version>"))` as the first Maestro line and the same `<version>` sentence.

- [ ] **Step 4: F3/F4 statements in concepts.md**

- `docs/concepts.md:144` (end of the Determinism Rule paragraph) — append *"Since this release the engine checks the recorded step name on every replayed step: a renamed, removed or reordered activity no longer silently replays another step's payload — the run stands down with `StandDownReason.NONDETERMINISTIC_HISTORY` (metric tag `nondeterministic_history`), nothing is written, the `maestro.standdown` meter increments and the WARN names the expected and recorded step. Gate such edits behind `workflow.version()` (below)."*
- `docs/concepts.md:280` — replace the sentence "`awaitSignal(name, timeout)` is unaffected by any of this: its timeout is an in-memory recheck loop, not a durable timer row, so there is nothing for `cancelTimer` to reach." with: *"`awaitSignal(name, timeout)` arms a durable timer row for its timeout (timer ID `await-signal-<seq>`, where `seq` is the sequence the await allocated), so the deadline survives restarts and is honoured from the original arming time. `cancelTimer` does reach it: a cancelled await timer ends the await with `SignalTimeoutException` on its next wake, because the timer row is the arbiter and a non-`PENDING` row means the deadline is spent."*
- `docs/concepts.md:192-212` ("Awaiting a signal") — add after the snippet: *"The timeout is durable: it is a timer row in `maestro_workflow_timer`, so a crash does not restart the clock, and in tests `env.advanceTime(timeout)` fires it deterministically."*

- [ ] **Step 5: Ledger and release notes**

`docs/release-notes.md` `## Unreleased`: `### Added — maestro-bom platform` (one paragraph) and confirm every task above left its entry. `docs/open-issues.md` §4 index: append one sentence: *"Issue 25 (`maestro.worker.*` inert) is **now resolved** — implemented, see its section."*

- [ ] **Step 6: Final gate and commit**

Run: `./gradlew build --console=plain` → BUILD SUCCESSFUL. Also `./gradlew :maestro-core:dependencies --configuration compileClasspath | grep -c springframework` → `0`.

```bash
git add maestro-bom settings.gradle.kts README.md CONTRIBUTING.md docs
git commit -m "docs: BOM module, accurate build commands, rewritten competitive table, durable-timeout and drift statements (F13, §7, F3/F4 docs)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review notes

- **Spec coverage:** F1 → Tasks 1–2; F5/F9 → Task 4; F6 → Task 3; F8/#25 → Tasks 5–6; F10 → Task 6; F11 → Task 7; F12 → Task 8; F13 docs + §7 → Task 9; F3/F4 docs → Task 9 Step 4; sample tests (decision 9) → Task 2 Step 6; Review Focus 4/5 from the cycle README → Task 1 Step 7 and Task 4 Step 5.
- **Type consistency:** `Supplier<Object> workflowFactory` (Tasks 1, 2); `TaskQueueLimits`/`TaskQueueGovernor`/`availableWorkflowSlots → OptionalInt` (Task 5 core, starter, IT); `SchemaMode`/`MaestroSchemaInitializer(DataSource, SchemaMode, ClassLoader)` (Task 4 module test, coexist IT, `PostgresIntegrationSupport`); `WorkflowHandle<R>`/`getWorkflow(String, Class<R>)` (Task 7 tests and docs); `WorkflowContextInternals` statics (Task 8).
- **Ordering dependencies:** Task 3 before 4 (`StoreProperties` shape); Task 4 before 6 (`V202` location, coexist IT version list); Task 1 before 2, 5, 7. Task 3 Step 1 forward-references `maestro.store.schema.mode=none` as an inert unknown property until Task 4 lands — stated inline.
- **Proportion:** code appears only as test names/assertions, signatures, one SQL migration body and verbatim doc copy the spec fixes.
