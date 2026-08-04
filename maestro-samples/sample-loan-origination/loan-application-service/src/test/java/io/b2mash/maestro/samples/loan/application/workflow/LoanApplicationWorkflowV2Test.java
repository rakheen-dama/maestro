package io.b2mash.maestro.samples.loan.application.workflow;

import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.samples.loan.application.activity.FundingActivities;
import io.b2mash.maestro.samples.loan.application.activity.LoanActivities;
import io.b2mash.maestro.samples.loan.application.activity.LoanMessagingActivities;
import io.b2mash.maestro.samples.loan.application.activity.impl.InMemoryLoanActivities;
import io.b2mash.maestro.samples.loan.application.activity.impl.SimulatedFundingActivities;
import io.b2mash.maestro.samples.loan.application.domain.DocumentUploaded;
import io.b2mash.maestro.samples.loan.application.domain.LoanApplication;
import io.b2mash.maestro.samples.loan.application.domain.Signature;
import io.b2mash.maestro.samples.loan.application.domain.UnderwritingDecision;
import io.b2mash.maestro.samples.loan.application.domain.UnderwritingRequested;
import io.b2mash.maestro.samples.loan.application.domain.VerificationRequested;
import io.b2mash.maestro.samples.loan.application.domain.VerificationResult;
import io.b2mash.maestro.test.InMemoryDistributedLock;
import io.b2mash.maestro.test.InMemorySignalNotifier;
import io.b2mash.maestro.test.InMemoryWorkflowMessaging;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The versioning guarantee this demo is built on: a loan already in flight when
 * the v2 jar is deployed keeps the v1 verification path, byte for byte in its
 * event log, while a loan started afterwards fans its three verifications out
 * in parallel.
 *
 * <h2>How both versions of one class run in one JVM</h2>
 * <p>{@code src/v2/java} holds a {@code LoanApplicationWorkflow} at the
 * <em>same</em> fully-qualified name as {@code src/main/java} — that is what a
 * versioned redeploy is. To compare the two directly, this test loads the v2
 * class through a child-first {@link URLClassLoader} over the {@code v2} source
 * set's classes directory (handed in by Gradle as
 * {@code -Dmaestro.demo.v2ClassesDir}); every other class still resolves from
 * the parent loader, so both implementations share one copy of the activities,
 * the domain records and the engine. {@link #v2ClassIsGenuinelyADifferentClass()}
 * pins that the child loader really did shadow v1 — without it, a
 * misconfigured classpath would make every assertion here pass vacuously
 * against v1 code.
 *
 * <h2>What "the same event sequence" means</h2>
 * <p>Events are compared as {@code sequence:eventType:stepName} — the exact
 * fingerprint {@code DeterminismChecker} uses. Payloads are excluded on
 * purpose: a different recorded version is a different history, not a divergent
 * path.
 *
 * @see LoanApplicationWorkflow
 */
@DisplayName("v2 (parallel verification) behind workflow.version()")
class LoanApplicationWorkflowV2Test {

    private static final String WORKFLOW_FQN =
            "io.b2mash.maestro.samples.loan.application.workflow.LoanApplicationWorkflow";
    private static final String CHANGE_ID = "parallel-verification";
    private static final String WORKFLOW_TYPE = "loan-application";
    private static final String TASK_QUEUE = "loan-application";

    /** {@code DefaultWorkflowOperations.BRANCH_MULTIPLIER} — branch sequence partition size. */
    private static final int BRANCH_MULTIPLIER = 1000;

    private static final Duration SETTLE = Duration.ofSeconds(15);
    private static final List<String> BORROWERS = List.of("borrower-1", "borrower-2");
    private static final List<String> REQUIRED_DOCS = List.of("payslip", "bank-statement");

    private InMemoryWorkflowStore store;
    private InMemoryDistributedLock lock;
    private InMemoryWorkflowMessaging messaging;
    private InMemorySignalNotifier notifier;
    private PayloadSerializer serializer;
    private ActivityProxyFactory proxyFactory;
    private RetryExecutor retryExecutor;
    private Map<Class<?>, Object> activityImpls;
    private RecordingMessagingActivities recordingMessaging;

    private Class<?> v2WorkflowClass;
    private final List<WorkflowExecutor> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Fast test timeouts; the gate timeout is waited out in real time on
        // the not-withdrawn path, twice per run.
        LoanTimeouts.configure(
                Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofMillis(200));

        store = new InMemoryWorkflowStore();
        lock = new InMemoryDistributedLock();
        messaging = new InMemoryWorkflowMessaging();
        notifier = new InMemorySignalNotifier();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        proxyFactory = new ActivityProxyFactory();
        retryExecutor = new RetryExecutor();
        recordingMessaging = new RecordingMessagingActivities();

        activityImpls = new LinkedHashMap<>();
        activityImpls.put(LoanActivities.class, new InMemoryLoanActivities());
        activityImpls.put(LoanMessagingActivities.class, recordingMessaging);
        activityImpls.put(FundingActivities.class, new SimulatedFundingActivities());

        v2WorkflowClass = loadV2WorkflowClass();
    }

    @AfterEach
    void tearDown() {
        nodes.forEach(WorkflowExecutor::shutdown);
        nodes.clear();
        LoanTimeouts.reset();
    }

    // ── Guard: the fixture really is running two different implementations ──

    @Test
    @DisplayName("the v2 class is a genuinely different class at the same name")
    void v2ClassIsGenuinelyADifferentClass() {
        assertAll(
                () -> assertEquals(WORKFLOW_FQN, v2WorkflowClass.getName()),
                () -> assertEquals(WORKFLOW_FQN, LoanApplicationWorkflow.class.getName()),
                () -> assertNotSame(LoanApplicationWorkflow.class, v2WorkflowClass,
                        "the child-first loader must shadow main's class — if it does not, "
                                + "every other assertion in this class passes vacuously against v1"),
                () -> assertNotSame(LoanApplicationWorkflow.class.getClassLoader(),
                        v2WorkflowClass.getClassLoader()));
    }

    // ── Pin (a): the in-flight loan keeps v1 behaviour, event for event ─────

    @Test
    @DisplayName("a loan in flight when v2 is deployed replays the sequential path with an identical event log")
    void inFlightLoanKeepsTheSequentialPathAndTheSameEventSequence() {
        // Reference: a whole loan run start-to-finish on v1 code only.
        var nodeV1 = node("node-v1");
        var reference = startLoan(nodeV1, "loan-reference", LoanApplicationWorkflow.class);
        driveVerificationsSequentially(nodeV1, reference);
        driveToFunded(nodeV1, reference);
        awaitStatus(reference, WorkflowStatus.COMPLETED);
        var referenceLog = eventLog(reference);

        // Migrated: the same script, but the node is replaced mid-verification
        // by one running v2 code.
        var migrated = startLoan(nodeV1, "loan-migrated", LoanApplicationWorkflow.class);
        nodeV1.deliverSignal(migrated.workflowId, "verification.result",
                approved(migrated.applicationId, "credit"));
        awaitVerificationsConsumed(migrated, 1);
        awaitStatus(migrated, WorkflowStatus.WAITING_SIGNAL);

        nodeV1.shutdown();
        nodes.remove(nodeV1);
        await().atMost(SETTLE).until(() -> store.getRecoverableInstances().stream()
                .anyMatch(i -> i.workflowId().equals(migrated.workflowId)));
        var nodeV2 = node("node-v2");
        assertEquals(1, nodeV2.recoverWorkflows(Map.of(WORKFLOW_TYPE, registration(v2WorkflowClass))),
                "the replacement node must adopt the in-flight loan");

        nodeV2.deliverSignal(migrated.workflowId, "verification.result",
                approved(migrated.applicationId, "employment"));
        awaitVerificationsConsumed(migrated, 2);
        nodeV2.deliverSignal(migrated.workflowId, "verification.result",
                approved(migrated.applicationId, "appraisal"));
        awaitVerificationsConsumed(migrated, 3);
        driveToFunded(nodeV2, migrated);
        awaitStatus(migrated, WorkflowStatus.COMPLETED);

        var migratedLog = eventLog(migrated);

        // Printed so the sequences land in build/test-results/*.xml and can be
        // archived under demo/.evidence rather than merely asserted about.
        System.out.println("v1-only reference event log: " + referenceLog);
        System.out.println("in-flight-across-v2 event log: " + migratedLog);

        assertAll(
                () -> assertEquals(referenceLog, migratedLog,
                        "an in-flight loan must produce the SAME event sequence under v2 code "
                                + "as it did under v1 — not merely complete"),
                () -> assertTrue(events(migrated).stream()
                                .noneMatch(e -> e.eventType() == EventType.VERSION_MARKER),
                        "a history that predates the change must resolve to DEFAULT_VERSION "
                                + "without consuming a sequence slot, so no marker is written"),
                () -> assertTrue(events(migrated).stream()
                                .noneMatch(e -> "$maestro:parallel".equals(e.stepName())),
                        "the in-flight loan must not fork the parallel branches"));
    }

    // ── Pin (b): a fresh loan under v2 fans out ────────────────────────────

    @Test
    @DisplayName("a loan started on v2 records the marker and forks verification ∥ documents")
    void freshLoanUnderV2TakesTheParallelBranch() {
        var run = runFreshLoanOnV2();

        var markers = events(run).stream()
                .filter(e -> e.eventType() == EventType.VERSION_MARKER)
                .toList();
        var forks = events(run).stream()
                .filter(e -> "$maestro:parallel".equals(e.stepName()))
                .toList();

        assertAll(
                () -> assertEquals(1, markers.size(), "exactly one durable version marker"),
                () -> assertEquals(CHANGE_ID,
                        markers.getFirst().payload().path("changeId").stringValue()),
                () -> assertEquals(2, markers.getFirst().payload().path("version").intValue()),
                () -> assertEquals(1, markers.getFirst().sequenceNumber(),
                        "the version is resolved before the first step, so every loan already "
                                + "past its first activity is pinned to the v1 path"),
                () -> assertEquals(1, forks.size(), "one parallel fork"),
                () -> assertEquals(2, forks.getFirst().payload().path("branchCount").intValue()),
                () -> assertEquals(3, verificationSignalEvents(run).size(),
                        "exactly three verification results consumed — no branch double-takes"),
                () -> assertEquals(2, documentSignalEvents(run).size()));
    }

    // ── Pin (c): branch sequence allocation ────────────────────────────────

    @Test
    @DisplayName("each branch allocates from parentSeq*1000 + (i+1)*1000")
    void parallelBranchesAllocateFromThePartitionedSequenceSpace() {
        var run = runFreshLoanOnV2();

        var parentSeq = events(run).stream()
                .filter(e -> "$maestro:parallel".equals(e.stepName()))
                .findFirst().orElseThrow().sequenceNumber();
        var branch0Base = parentSeq * BRANCH_MULTIPLIER + 1 * BRANCH_MULTIPLIER;
        var branch1Base = parentSeq * BRANCH_MULTIPLIER + 2 * BRANCH_MULTIPLIER;
        var afterJoin = parentSeq * BRANCH_MULTIPLIER + 3 * BRANCH_MULTIPLIER;

        var verificationSeqs = sequences(verificationSignalEvents(run));
        var documentSeqs = sequences(documentSignalEvents(run));
        var afterJoinSeqs = events(run).stream()
                .map(WorkflowEvent::sequenceNumber)
                .filter(seq -> seq >= afterJoin)
                .sorted()
                .toList();

        System.out.println("fresh-v2 event log: " + eventLog(run));
        System.out.println("parallel fork at parent seq " + parentSeq
                + "; verification branch " + verificationSeqs + "; document branch " + documentSeqs);

        assertAll(
                // Branch 0 awaits verification.result three times, back to back,
                // from the first slot of its own partition.
                () -> assertEquals(
                        List.of(branch0Base + 1, branch0Base + 2, branch0Base + 3),
                        verificationSeqs,
                        "branch 0 must allocate from parentSeq*1000 + 1*1000"),
                // Branch 1's collectSignals interleaves currentTime side effects
                // with its two awaits, so pin containment rather than exact slots.
                () -> assertTrue(documentSeqs.stream()
                                .allMatch(seq -> seq > branch1Base && seq < afterJoin),
                        "branch 1 must allocate from parentSeq*1000 + 2*1000, got " + documentSeqs),
                () -> assertEquals(2, documentSeqs.size()),
                () -> assertEquals(afterJoin + 1, afterJoinSeqs.getFirst(),
                        "the parent must resume on the first slot past every branch partition"));
    }

    // ── Why the fan-out is NOT one branch per verification type ────────────

    @Test
    @DisplayName("two branches awaiting the SAME signal name collide on the parking key")
    void concurrentBranchesOnOneSignalNameAreUnsupported() {
        var node = node("node-same-name");
        var workflowId = "same-name-1";
        node.startWorkflow(workflowId, "same-name-branches", "test", null,
                workflowImpl(SameSignalNameBranchesWorkflow.class),
                workflowMethod(SameSignalNameBranchesWorkflow.class));

        await().atMost(SETTLE).until(() ->
                store.getInstance(workflowId).map(i -> i.status().isTerminal()).orElse(false));

        var instance = store.getInstance(workflowId).orElseThrow();
        var outcomes = String.valueOf(instance.output());
        System.out.println("same-signal-name branch outcomes: " + outcomes);

        assertTrue(outcomes.contains("Parking key") && outcomes.contains("already occupied"),
                "ParkingLot keys on workflowId + \":signal:\" + signalName and holds one waiter "
                        + "per key, so one branch per verification type is impossible on this "
                        + "engine. Actual branch outcomes: " + outcomes);
    }

    /**
     * The <em>second</em> way one-branch-per-verification-type fails, and the
     * reason the same-signal-name rule is not merely about the parking key: when
     * the signal has <b>already arrived</b>, no branch parks at all, so
     * {@link ParkingLot}'s one-waiter-per-key guard never fires. Every branch
     * instead takes {@code getUnconsumedSignals(...).getFirst()} — the
     * <em>same row</em> — and {@code SignalManager.consumeSignal} deliberately
     * <em>proceeds</em> when its {@code markSignalConsumed} CAS loses, because
     * the {@code SIGNAL_RECEIVED} event it already appended is the durable
     * memoized truth for that sequence. Both branches therefore return the same
     * verification result, and a workflow that believed it had collected two
     * distinct verifications has collected one twice.
     *
     * <p><b>Why the store is proxied.</b> Left to chance this is a race: if
     * branch B happens to query after branch A's CAS, it finds nothing pending
     * and parks instead, which is a different (also broken, also timing-
     * dependent) outcome. The proxy holds both branches at the read until each
     * has the row in hand, forcing the interleaving the Javadoc describes so the
     * assertion pins the documented behaviour rather than the luckier half of a
     * race. Nothing about the engine is stubbed — only the moment the two reads
     * happen relative to each other.
     */
    @Test
    @DisplayName("with the signal already pending, both same-name branches consume the SAME row")
    void concurrentBranchesOnOneSignalNameConsumeTheSameSignalRow() {
        // Released once both branches have read the pending-signal list, so
        // neither can CAS before the other has seen the row.
        var bothRead = new CountDownLatch(2);
        var latchedStore = latchOnRead(store, "dup", bothRead);
        var node = new WorkflowExecutor(latchedStore, lock, messaging, notifier, serializer,
                "node-same-name-pending");
        nodes.add(node);

        var workflowId = "same-name-pending-1";
        // Delivered BEFORE the workflow starts: an orphan signal, adopted at
        // start, so it is already unconsumed and pending when both branches
        // reach their await and neither of them parks.
        node.deliverSignal(workflowId, "dup", "only-one");
        node.startWorkflow(workflowId, "same-name-branches", "test", null,
                workflowImpl(SameSignalNameBranchesWorkflow.class),
                workflowMethod(SameSignalNameBranchesWorkflow.class));

        await().atMost(SETTLE).until(() ->
                store.getInstance(workflowId).map(i -> i.status().isTerminal()).orElse(false));

        var instance = store.getInstance(workflowId).orElseThrow();
        var outcomes = String.valueOf(instance.output());
        var signalEvents = store.getEvents(instance.id()).stream()
                .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED)
                .toList();
        System.out.println("same-signal-name pending-signal outcomes: " + outcomes);
        System.out.println("SIGNAL_RECEIVED events: " + sequences(signalEvents));

        assertAll(
                () -> assertEquals(2, signalEvents.size(),
                        "one delivered signal, but each branch appends its own SIGNAL_RECEIVED "
                                + "at its own branch sequence — the row is consumed twice"),
                () -> assertTrue(outcomes.contains("received:only-one")
                                && outcomes.lastIndexOf("received:only-one")
                                   != outcomes.indexOf("received:only-one"),
                        "both branches must return the SAME payload — one signal row satisfying "
                                + "two concurrent awaits is exactly why one branch per "
                                + "verification type cannot work. Actual outcomes: " + outcomes));
    }

    /**
     * Wraps a {@link WorkflowStore} so that every
     * {@code getUnconsumedSignals(_, signalName)} counts the latch down and then
     * waits on it before returning. A dynamic proxy rather than a hand-written
     * decorator so the SPI can grow methods without this fixture rotting.
     */
    private static WorkflowStore latchOnRead(WorkflowStore delegate, String signalName,
                                             CountDownLatch bothRead) {
        return (WorkflowStore) java.lang.reflect.Proxy.newProxyInstance(
                WorkflowStore.class.getClassLoader(), new Class<?>[]{WorkflowStore.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if ("getUnconsumedSignals".equals(method.getName())
                            && args != null && args.length == 2 && signalName.equals(args[1])) {
                        bothRead.countDown();
                        // Bounded: if only one branch ever gets here the test
                        // degrades to the unforced race rather than hanging,
                        // and the assertion — not a timeout — reports it.
                        bothRead.await(5, TimeUnit.SECONDS);
                    }
                    return result;
                });
    }

    /**
     * Fixture for {@link #concurrentBranchesOnOneSignalNameAreUnsupported()} and
     * {@link #concurrentBranchesOnOneSignalNameConsumeTheSameSignalRow()}:
     * two branches awaiting one signal name. Each branch reports its own
     * outcome instead of throwing, because {@code parallel()} rethrows only the
     * <em>first</em> branch error and which branch loses the park race varies.
     */
    @DurableWorkflow(name = "same-name-branches", taskQueue = "test")
    public static class SameSignalNameBranchesWorkflow {

        /** @return one outcome string per branch */
        @WorkflowMethod
        public List<String> run() {
            return WorkflowContext.current()
                    .parallel(List.<Callable<String>>of(awaitDup(), awaitDup()));
        }

        private static Callable<String> awaitDup() {
            return () -> {
                try {
                    return "received:" + WorkflowContext.current()
                            .awaitSignal("dup", String.class, Duration.ofSeconds(2));
                } catch (RuntimeException e) {
                    return e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            };
        }
    }

    // ── Shared script ──────────────────────────────────────────────────────

    private Run runFreshLoanOnV2() {
        var nodeV2 = node("node-v2");
        var run = startLoan(nodeV2, "loan-fresh-v2", v2WorkflowClass);

        // Nothing delivered yet: both branches park concurrently on different
        // signal names — the concurrent-park case that used to collide on the
        // instance row (InstanceStatusWriter bounded retry).
        awaitStatus(run, WorkflowStatus.WAITING_SIGNAL);

        // Documents first, so the document branch runs to completion while the
        // verification branch is still parked. Under v1 this ordering is
        // impossible: documents are not collected until all three
        // verifications are in.
        deliverDocuments(nodeV2, run);
        await().atMost(SETTLE).until(() -> documentSignalEvents(run).size() == 2);

        driveVerificationsSequentially(nodeV2, run);
        driveUnderwritingAndFunding(nodeV2, run);
        awaitStatus(run, WorkflowStatus.COMPLETED);
        return run;
    }

    /** Delivers the three results one at a time, each fully consumed before the next. */
    private void driveVerificationsSequentially(WorkflowExecutor node, Run run) {
        var types = List.of("credit", "employment", "appraisal");
        for (int i = 0; i < types.size(); i++) {
            node.deliverSignal(run.workflowId, "verification.result",
                    approved(run.applicationId, types.get(i)));
            awaitVerificationsConsumed(run, i + 1);
        }
    }

    private void driveToFunded(WorkflowExecutor node, Run run) {
        deliverDocuments(node, run);
        driveUnderwritingAndFunding(node, run);
    }

    private void deliverDocuments(WorkflowExecutor node, Run run) {
        node.deliverSignal(run.workflowId, "document.uploaded",
                new DocumentUploaded(run.applicationId, "payslip", BORROWERS.get(0)));
        node.deliverSignal(run.workflowId, "document.uploaded",
                new DocumentUploaded(run.applicationId, "bank-statement", BORROWERS.get(1)));
    }

    private void driveUnderwritingAndFunding(WorkflowExecutor node, Run run) {
        node.deliverSignal(run.workflowId, "underwriting.decision",
                new UnderwritingDecision(run.applicationId, 1, "APPROVED", List.of()));
        node.deliverSignal(run.workflowId, "package.signed",
                new Signature(run.applicationId, BORROWERS.get(0)));
        node.deliverSignal(run.workflowId, "package.signed",
                new Signature(run.applicationId, BORROWERS.get(1)));
    }

    // ── Fixture ────────────────────────────────────────────────────────────

    private record Run(String applicationId, String workflowId, UUID instanceId) {}

    private WorkflowExecutor node(String name) {
        var executor = new WorkflowExecutor(store, lock, messaging, notifier, serializer, name);
        nodes.add(executor);
        return executor;
    }

    private Run startLoan(WorkflowExecutor node, String applicationId, Class<?> workflowClass) {
        var workflowId = "loan-" + applicationId;
        var input = new LoanApplication(applicationId, BORROWERS, 400_000, 100_000, 500_000,
                REQUIRED_DOCS);
        var instanceId = node.startWorkflow(workflowId, WORKFLOW_TYPE, TASK_QUEUE, input,
                workflowImpl(workflowClass), workflowMethod(workflowClass));
        return new Run(applicationId, workflowId, instanceId);
    }

    private WorkflowRegistration registration(Class<?> workflowClass) {
        return new WorkflowRegistration(WORKFLOW_TYPE, TASK_QUEUE,
                workflowImpl(workflowClass), workflowMethod(workflowClass));
    }

    /** Instantiates the workflow and injects a memoizing proxy per {@code @ActivityStub}. */
    private Object workflowImpl(Class<?> workflowClass) {
        Object impl;
        try {
            var constructor = workflowClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            impl = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + workflowClass.getName(), e);
        }
        for (var field : workflowClass.getDeclaredFields()) {
            var stub = field.getAnnotation(ActivityStub.class);
            if (stub == null) {
                continue;
            }
            var activityInterface = field.getType();
            var activityImpl = activityImpls.get(activityInterface);
            if (activityImpl == null) {
                throw new IllegalStateException(
                        "No activity impl registered for " + activityInterface.getName());
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            var proxy = proxyFactory.createProxy((Class) activityInterface, activityImpl, store,
                    lock, messaging, RetryPolicy.fromAnnotation(stub.retryPolicy()),
                    Duration.parse(stub.startToCloseTimeout()), serializer, retryExecutor);
            try {
                field.setAccessible(true);
                field.set(impl, proxy);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot inject " + field.getName(), e);
            }
        }
        return impl;
    }

    private static Method workflowMethod(Class<?> workflowClass) {
        for (var method : workflowClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(WorkflowMethod.class)) {
                return method;
            }
        }
        throw new IllegalStateException("No @WorkflowMethod on " + workflowClass.getName());
    }

    /**
     * Loads {@code LoanApplicationWorkflow} from the {@code v2} source set's
     * output, child-first for that one name so it shadows main's class while
     * everything else — the engine, the activities, the domain records — stays
     * on the parent loader and is therefore shared between both versions.
     */
    private static Class<?> loadV2WorkflowClass() {
        var dirs = System.getProperty("maestro.demo.v2ClassesDir");
        if (dirs == null || dirs.isBlank()) {
            throw new IllegalStateException(
                    "-Dmaestro.demo.v2ClassesDir is not set — see this module's build.gradle.kts");
        }
        var urls = new ArrayList<URL>();
        for (var dir : dirs.split(File.pathSeparator)) {
            try {
                urls.add(Path.of(dir).toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Bad v2 classes dir: " + dir, e);
            }
        }
        var loader = new V2ClassLoader(urls.toArray(URL[]::new),
                LoanApplicationWorkflowV2Test.class.getClassLoader());
        try {
            return loader.loadClass(WORKFLOW_FQN);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "No v2 LoanApplicationWorkflow under " + dirs, e);
        }
    }

    /** Parent-first for everything except the one class the v2 deployment replaces. */
    private static final class V2ClassLoader extends URLClassLoader {

        private V2ClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!WORKFLOW_FQN.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    // ── Assertions helpers ─────────────────────────────────────────────────

    private List<WorkflowEvent> events(Run run) {
        return store.getEvents(run.instanceId);
    }

    private List<WorkflowEvent> verificationSignalEvents(Run run) {
        return signalEvents(run, "verification.result");
    }

    private List<WorkflowEvent> documentSignalEvents(Run run) {
        return signalEvents(run, "document.uploaded");
    }

    private List<WorkflowEvent> signalEvents(Run run, String signalName) {
        return events(run).stream()
                .filter(e -> e.eventType() == EventType.SIGNAL_RECEIVED)
                .filter(e -> ("$maestro:awaitSignal:" + signalName).equals(e.stepName()))
                .toList();
    }

    private static List<Integer> sequences(List<WorkflowEvent> events) {
        return events.stream().map(WorkflowEvent::sequenceNumber).sorted().toList();
    }

    /** {@code sequence:eventType:stepName} — DeterminismChecker's fingerprint. */
    private List<String> eventLog(Run run) {
        return events(run).stream()
                .sorted(java.util.Comparator.comparingInt(WorkflowEvent::sequenceNumber))
                .map(e -> "%d:%s:%s".formatted(e.sequenceNumber(), e.eventType(), e.stepName()))
                .toList();
    }

    private void awaitVerificationsConsumed(Run run, int count) {
        await().atMost(SETTLE).until(() -> verificationSignalEvents(run).size() >= count);
    }

    private void awaitStatus(Run run, WorkflowStatus status) {
        try {
            await().atMost(SETTLE).until(() ->
                    store.getInstance(run.workflowId).map(i -> i.status() == status).orElse(false));
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            var failure = events(run).stream()
                    .filter(ev -> ev.eventType() == EventType.WORKFLOW_FAILED)
                    .map(ev -> String.valueOf(ev.payload()))
                    .toList();
            throw new AssertionError("%s never reached %s — it is %s; failure=%s; events %s"
                    .formatted(run.workflowId, status,
                            store.getInstance(run.workflowId).map(i -> i.status().toString())
                                    .orElse("<missing>"),
                            failure, eventLog(run)), e);
        }
    }

    private static VerificationResult approved(String applicationId, String type) {
        return new VerificationResult(applicationId, type, true, "ok");
    }

    /** In-memory stand-in for the Kafka-publishing activities. */
    static final class RecordingMessagingActivities implements LoanMessagingActivities {

        final List<VerificationRequested> verificationRequests = new CopyOnWriteArrayList<>();
        final List<UnderwritingRequested> underwritingRequests = new CopyOnWriteArrayList<>();

        @Override
        public void requestVerifications(LoanApplication application) {
            for (var type : VERIFICATION_TYPES) {
                verificationRequests.add(new VerificationRequested(
                        application.applicationId(), type, application.amount()));
            }
        }

        @Override
        public void requestUnderwriting(LoanApplication application, int round) {
            underwritingRequests.add(new UnderwritingRequested(
                    application.applicationId(), round, application.amount(),
                    application.income(), application.propertyValue(), true));
        }
    }
}
