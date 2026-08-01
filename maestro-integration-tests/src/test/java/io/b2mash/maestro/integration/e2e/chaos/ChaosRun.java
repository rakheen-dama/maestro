package io.b2mash.maestro.integration.e2e.chaos;

import io.b2mash.maestro.integration.e2e.chaos.NodeRole.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end orchestrator for one chaos/soak run (chaos-harness-design.md §1–§9).
 * Boots the cluster, starts the metrics sampler and (for non-golden modes) the
 * chaos controller and workload generator, then at {@code T_gen_end} hard-stops
 * chaos, heals all, drains, and runs the authoritative invariant check + Issue 11
 * side-effect census. All evidence is written under a per-run directory and the
 * small summary artifacts are mirrored to the SDD evidence tree.
 *
 * <h2>Thread Safety</h2>
 * <p>Orchestration is driven from the calling (test) thread; the controller,
 * sampler and workload scripts run on their own threads and communicate only
 * through the store and thread-safe evidence writers.
 */
public final class ChaosRun {

    private static final Logger log = LoggerFactory.getLogger(ChaosRun.class);

    private final ChaosConfig config;

    /** @param config the resolved run configuration */
    public ChaosRun(ChaosConfig config) {
        this.config = config;
    }

    /** Immutable outcome of a run. */
    public record Result(List<InvariantChecker.Violation> violations,
                         SideEffectCensus.Result census,
                         long seed,
                         Path runDir) {

        /** @return true if the run passed (no hard invariant violations). */
        public boolean passed() {
            return violations.isEmpty();
        }

        /** @return a JUnit failure message naming invariants, seed and dump dir. */
        public String failureMessage() {
            var sb = new StringBuilder("Chaos run FAILED (seed=").append(seed)
                    .append(", dumps=").append(runDir.resolve("failures")).append("):\n");
            for (var v : violations) {
                sb.append("  [").append(v.invariant()).append("] ").append(v.detail())
                        .append(" -> ").append(v.workflowIds()).append('\n');
            }
            sb.append("Re-run: ./gradlew :maestro-integration-tests:e2eTest -Dmaestro.chaos.seed=")
                    .append(seed);
            return sb.toString();
        }
    }

    /**
     * Executes the full run and returns its result. The caller asserts on
     * {@link Result#passed()}.
     *
     * @return the run result
     */
    public Result execute() {
        var identity = RunIdentity.capture(config.seed(), config.mode());
        var evidence = new EvidenceWriter(identity, config.evidenceRoot());
        System.out.println("[chaos] ================= CHAOS RUN =================");
        System.out.println("[chaos] mode=" + config.mode() + " seed=" + config.seed()
                + " runId=" + identity.runId());
        System.out.println("[chaos] re-run: ./gradlew :maestro-integration-tests:e2eTest "
                + "-Dmaestro.chaos.seed=" + config.seed());

        var master = new SplittableRandom(config.seed());
        var driverRng = master.split();
        var controllerRng = master.split();

        List<InvariantChecker.Violation> violations = new ArrayList<>();
        SideEffectCensus.Result census;

        try (var cluster = new ChaosCluster(config, evidence)) {
            cluster.start();

            var sampler = new MetricsSampler(cluster, config, evidence);
            var driver = new WorkloadDriver(cluster, config, evidence, driverRng);
            var controller = new ChaosController(cluster, config.mode(), evidence, controllerRng);
            var periodic = new PeriodicChecker(cluster, evidence, driver);

            sampler.start();
            periodic.start();

            // The controller must never die silently (FAIL LOUDLY): the
            // 2h-soak controller died at 16:08Z on a failed VERIFY_A replace
            // and chaos stopped for the remaining ~105 min with nothing
            // failing the run. A controller death now (a) is recorded and
            // ERROR-logged, (b) promptly aborts the concurrent generation by
            // interrupting the orchestrating thread (the interrupt-hardened
            // pacer turns that into a loud abort, never a hot loop), and
            // (c) fails the run with the controller's throwable as cause.
            var controllerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var controllerStopRequested = new java.util.concurrent.atomic.AtomicBoolean();
            Thread orchestrator = Thread.currentThread();
            Thread controllerThread = new Thread(() -> {
                try {
                    controller.run(config.durationMinutes());
                } catch (Throwable t) {
                    if (controllerStopRequested.get()) {
                        log.info("[chaos] controller stopped after a generation failure: {}",
                                t.toString());
                        return;
                    }
                    controllerFailure.set(t);
                    log.error("[chaos] CONTROLLER DIED mid-schedule — failing the run "
                            + "(chaos silently stopping mid-soak is not allowed)", t);
                    orchestrator.interrupt();
                }
            }, "chaos-controller");
            controllerThread.start();

            // Workload generation blocks for the window; chaos runs concurrently.
            try {
                driver.generate(config.durationMinutes());
            } catch (RuntimeException generationFailure) {
                Throwable death = controllerFailure.get();   // read BEFORE stopping it
                controllerStopRequested.set(true);
                controllerThread.interrupt();   // don't sit out the rest of the window
                joinQuietly(controllerThread);
                Thread.interrupted();   // clear any controller-death interrupt
                if (death != null) {
                    var e = new IllegalStateException(
                            "CHAOS CONTROLLER DIED mid-schedule — chaos stopped; run aborted "
                            + "(fail-loudly)", death);
                    e.addSuppressed(generationFailure);
                    throw e;
                }
                throw generationFailure;
            }
            joinQuietly(controllerThread);
            controller.close();
            Throwable death = controllerFailure.get();
            if (death != null) {
                Thread.interrupted();   // clear the controller-death interrupt
                throw new IllegalStateException(
                        "CHAOS CONTROLLER DIED mid-schedule — chaos stopped; run aborted "
                        + "(fail-loudly)", death);
            }

            // Stop-before-verify: heal all, wait consumer groups stable, drain.
            log.info("[chaos] generation + chaos complete; healing");
            cluster.healAll();
            cluster.awaitConsumerGroup("verification-gateway", 2, Duration.ofSeconds(60));
            cluster.awaitConsumerGroup("underwriting", 2, Duration.ofSeconds(60));

            driver.awaitScriptsSettled(config.sampleTimeout().plusSeconds(60));
            drain(cluster, driver, config.drainSla());

            periodic.stop();

            var checker = new InvariantChecker(cluster, evidence, driver.ledger());
            var verify = checker.verifyAuthoritative();
            violations = verify.violations();

            var censusRunner = new SideEffectCensus(cluster, evidence, driver.ledger());
            census = censusRunner.run();

            writeSummary(evidence, driver.ledger(), verify, census, periodic);
            surface(verify, census, periodic);

            // Issue 12 benchmark tail (design §6, Ruling 3): soak-only, after
            // verify — chaos off, steady low rate, one measurement phase at 6
            // nodes then one at 3. The sampler keeps running so the phases land
            // in metrics.csv (liveNodes 6 vs 3, chaosActive=false).
            if (config.mode() == ChaosMode.SOAK && violations.isEmpty()) {
                benchmarkTail(cluster, driver, evidence);
            }

            sampler.stop();
            driver.close();
        }

        mirrorEvidence(evidence.runDir(), identity.runId());
        return new Result(violations, census, config.seed(), evidence.runDir());
    }

    // ------------------------------------------------------------------- drain

    private void drain(ChaosCluster cluster, WorkloadDriver driver, Duration drainSla) {
        log.info("[chaos] drain: waiting up to {}s for all workflows terminal in all services",
                drainSla.toSeconds());
        long end = System.nanoTime() + drainSla.toNanos();
        while (System.nanoTime() < end) {
            if (allInstancesTerminal(cluster, driver)) {
                log.info("[chaos] drain: all workflows terminal in all three services");
                return;
            }
            sleep(2000);
        }
        log.warn("[chaos] drain: SLA elapsed with non-terminal workflows (I1 will report)");
    }

    /**
     * True when every instance in all three service databases is terminal —
     * the same population I1's authoritative check covers, so drain waits for
     * exactly what verify asserts (underwriting children included: an
     * unanswered child needs its own double-timeout to reject).
     */
    private boolean allInstancesTerminal(ChaosCluster cluster, WorkloadDriver driver) {
        if (driver.ledger().isEmpty()) {
            return true;
        }
        for (Service svc : Service.values()) {
            try (Connection c = cluster.dataSource(svc).getConnection();
                 var ps = c.prepareStatement(
                         "SELECT COUNT(*) FROM maestro_workflow_instance "
                         + "WHERE status NOT IN ('COMPLETED','FAILED','TERMINATED')")) {
                try (var rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) != 0) {
                        return false;
                    }
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    // --------------------------------------------------------------- reporting

    private void writeSummary(EvidenceWriter evidence, List<LedgerEntry> ledger,
                              InvariantChecker.VerifyResult verify,
                              SideEffectCensus.Result census,
                              PeriodicChecker periodic) {
        var violations = verify.violations();
        var byPath = new LinkedHashMap<String, Long>();
        for (LedgerEntry e : ledger) {
            byPath.merge(e.path().name(), 1L, Long::sum);
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("seed", config.seed());
        summary.put("mode", config.mode().name());
        summary.put("durationMinutes", config.durationMinutes());
        summary.put("ratePerMinute", config.ratePerMinute());
        summary.put("workflowsSubmitted", ledger.size());
        summary.put("workflowsByPath", byPath);
        summary.put("verdict", violations.isEmpty() ? "PASS" : "FAIL");
        summary.put("violations", violations);
        // Ruling 3 mandatory finding: redelivered-but-unconsumed signal rows
        // (byte-identical consumed twin exists — Kafka at-least-once redelivery).
        summary.put("redeliveredUnconsumedSignals", verify.redeliveredUnconsumedSignals());
        summary.put("redeliveredUnconsumedSignalGroups", verify.redeliveredUnconsumedSignals().size());
        summary.put("sideEffectDuplicates", census.totalDuplicates());
        summary.put("explainedDuplicates", census.explained());
        summary.put("unexplainedDuplicates", census.unexplained());
        summary.put("unexplainedWorkflows", census.unexplainedWorkflows());
        summary.put("missingSagaCompensation", census.missingSagaCompensation());
        // Checker visibility accounting (§10): a run whose periodic checker was
        // blind for part of the window says so in its own summary.
        summary.put("periodicCheckerCycles", periodic.totalCycles());
        summary.put("periodicCheckerUnreachableCycles", periodic.unreachableCycles());
        summary.put("periodicCheckerMaxUnreachableStreak", periodic.maxUnreachableStreak());
        evidence.writeJson("run-summary.json", summary);
    }

    private void surface(InvariantChecker.VerifyResult verify, SideEffectCensus.Result census,
                         PeriodicChecker periodic) {
        var violations = verify.violations();
        System.out.println("[chaos] VERDICT: " + (violations.isEmpty() ? "PASS" : "FAIL"));
        if (periodic.unreachableCycles() > 0) {
            System.out.println("[chaos] !!! PERIODIC CHECKER WAS BLIND for "
                    + periodic.unreachableCycles() + " of " + periodic.totalCycles()
                    + " cycles (max streak " + periodic.maxUnreachableStreak()
                    + ") — invariants were unwatched during those windows");
        }
        System.out.println("[chaos] side-effect duplicates: total=" + census.totalDuplicates()
                + " explained=" + census.explained() + " unexplained=" + census.unexplained());
        if (!verify.redeliveredUnconsumedSignals().isEmpty()) {
            System.out.println("[chaos] FINDING (Ruling 3): redelivered-but-unconsumed signals "
                    + "(consumedTwin=true, Kafka at-least-once redelivery): "
                    + verify.redeliveredUnconsumedSignals());
        }
        if (census.unexplained() > 0) {
            System.out.println("[chaos] !!! UNEXPLAINED DUPLICATES (triage required, Q8): "
                    + census.unexplainedWorkflows());
        }
        if (!census.missingSagaCompensation().isEmpty()) {
            System.out.println("[chaos] !!! SAGA reserved a rate lock but no compensation logged: "
                    + census.missingSagaCompensation());
        }
        violations.forEach(v -> System.out.println("[chaos] VIOLATION [" + v.invariant() + "] "
                + v.detail() + " -> " + v.workflowIds()));
    }

    // ---------------------------------------------------------- benchmark tail

    /**
     * The Issue 12 vs-node-count benchmark tail (design §6): chaos off, a
     * steady low-rate workload, one measurement phase with all six nodes live,
     * then a graceful stop of one node per service and a second phase at three
     * nodes. Phase boundaries, node sets and workflow counts are recorded in
     * {@code benchmark-tail.json}; the rates themselves live in
     * {@code metrics.csv} rows (distinguished by {@code liveNodes} 6 vs 3 with
     * {@code chaosActive=false}). Phase length: 300s, compressible for smokes
     * via {@code -Dmaestro.chaos.tailPhaseSeconds}.
     */
    private void benchmarkTail(ChaosCluster cluster, WorkloadDriver driver, EvidenceWriter evidence) {
        int phaseSeconds = Integer.getInteger("maestro.chaos.tailPhaseSeconds", 300);
        int ratePerMinute = Integer.getInteger("maestro.chaos.tailRatePerMinute", 6);
        cluster.enterBenchmarkTailMode();
        var tail = new LinkedHashMap<String, Object>();
        tail.put("phaseSeconds", phaseSeconds);
        tail.put("ratePerMinute", ratePerMinute);
        log.info("[chaos] benchmark tail begins: {}s per phase at {}/min", phaseSeconds, ratePerMinute);

        tail.put("phase6NodesStartUtc", java.time.Instant.now().toString());
        int sixNodeWorkflows = driver.generateAt(ratePerMinute,
                Duration.ofSeconds(phaseSeconds), "tail6");
        tail.put("phase6NodesEndUtc", java.time.Instant.now().toString());
        tail.put("phase6NodesWorkflows", sixNodeWorkflows);

        // Gracefully stop one node of each service (the B instances). The
        // sampler's liveNodes column drops to 3 for the second phase.
        var stopped = List.of(NodeRole.LOAN_B, NodeRole.VERIFY_B, NodeRole.UW_B);
        for (NodeRole node : stopped) {
            cluster.stopGraceful(node);
        }
        tail.put("stoppedNodes", stopped.stream().map(Enum::name).toList());

        tail.put("phase3NodesStartUtc", java.time.Instant.now().toString());
        int threeNodeWorkflows = driver.generateAt(ratePerMinute,
                Duration.ofSeconds(phaseSeconds), "tail3");
        tail.put("phase3NodesEndUtc", java.time.Instant.now().toString());
        tail.put("phase3NodesWorkflows", threeNodeWorkflows);

        evidence.writeJson("benchmark-tail.json", tail);
        log.info("[chaos] benchmark tail complete: {} + {} workflows",
                sixNodeWorkflows, threeNodeWorkflows);
    }

    private void mirrorEvidence(Path runDir, String runId) {
        Path sddChaos = Path.of(System.getProperty("user.dir"))
                .resolve("../.superpowers/sdd/multi-instance/evidence/chaos").resolve(runId).normalize();
        Path task7 = Path.of(System.getProperty("user.dir"))
                .resolve("../.superpowers/sdd/multi-instance/evidence/task7").resolve(runId).normalize();
        List<String> summaryArtifacts = List.of("run-summary.json", "side-effects.json",
                "metrics.csv", "ledger.jsonl", "chaos-actions.jsonl", "calibration.json");
        for (Path dest : List.of(sddChaos, task7)) {
            try {
                Files.createDirectories(dest);
                for (String name : summaryArtifacts) {
                    Path src = runDir.resolve(name);
                    if (Files.exists(src)) {
                        Files.copy(src, dest.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Path failures = runDir.resolve("failures");
                if (Files.exists(failures)) {
                    copyTree(failures, dest.resolve("failures"));
                }
            } catch (IOException e) {
                log.warn("[chaos] evidence mirror to {} failed: {}", dest, e.toString());
            }
        }
    }

    private void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
