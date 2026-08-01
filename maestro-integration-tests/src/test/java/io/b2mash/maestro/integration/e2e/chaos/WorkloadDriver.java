package io.b2mash.maestro.integration.e2e.chaos;

import io.b2mash.maestro.integration.e2e.chaos.NodeRole.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The workload generator (chaos-harness-design.md §3): one virtual thread per
 * loan workflow, each running a {@link LoanPath} script of effect-checked HTTP
 * actions against the live-endpoint registry, appending its declared
 * expectations to the crash-safe ledger.
 *
 * <p>Every action retries across both nodes of its target service and re-posts
 * (bounded) if its store-visible consequence does not appear — the sample's
 * {@code auto.offset.reset=latest} listeners can silently skip an event
 * published mid-rebalance, so an action verifies its effect rather than trusting
 * an HTTP 202. Actions never fail the run; a workflow that cannot be driven to
 * its expected outcome surfaces as an I1 finding at drain, triaged against these
 * ledger notes first (Risk 1).
 *
 * <h2>Thread Safety</h2>
 * <p>Generation runs on one thread and owns the driver PRNG (deterministic given
 * the seed). Scripts run on a virtual-thread executor and share only thread-safe
 * collections and short-lived JDBC connections. The ledger writer serialises its
 * own appends.
 */
public final class WorkloadDriver {

    private static final Logger log = LoggerFactory.getLogger(WorkloadDriver.class);

    private static final long AMOUNT = 400_000;   // DTI 4.0 with income 100k -> human review;
    private static final long INCOME = 100_000;   // amount ends in 0 -> verifications approved
    private static final long PROPERTY = 500_000;
    private static final List<String> BORROWERS = List.of("borrower-a", "borrower-b");

    private final ChaosCluster cluster;
    private final ChaosConfig config;
    private final SplittableRandom rng;
    private final EvidenceWriter.JsonlWriter ledgerWriter;
    private final List<LedgerEntry> ledger = new CopyOnWriteArrayList<>();
    // O(1) add: the previous CopyOnWriteArrayList copied the whole array per
    // submission — O(n^2) cumulative, the GC-death amplifier of the pacer
    // runaway (investigation §5 mech. 2).
    private final java.util.Collection<Future<?>> futures =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger generatedCount =
            new java.util.concurrent.atomic.AtomicInteger();
    // In-flight bound (investigation §7.4): a stalled store back-pressures
    // generation instead of accumulating unbounded virtual threads. Sized by
    // Little's law: in-flight = arrival rate x script latency; the worst
    // legitimate script (CONDITIONS_LOOP with every effect check burning its
    // full 120s sampleTimeout under chaos) is ~12 min, so 15 min of arrivals
    // can never throttle real load (soak 20/min -> 300; PR gate 12/min -> 180;
    // floor 60 covers golden/tail rates).
    private final java.util.concurrent.Semaphore inFlight;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final tools.jackson.databind.ObjectMapper mapper;

    /**
     * @param cluster  the live cluster
     * @param config   run configuration
     * @param evidence evidence writer (opens {@code ledger.jsonl})
     * @param rng      the driver's PRNG split (deterministic given the seed)
     */
    public WorkloadDriver(ChaosCluster cluster, ChaosConfig config, EvidenceWriter evidence,
                          SplittableRandom rng) {
        this.cluster = cluster;
        this.config = config;
        this.rng = rng;
        this.mapper = evidence.mapper();
        this.ledgerWriter = evidence.openJsonl("ledger.jsonl");
        this.inFlight = new java.util.concurrent.Semaphore(
                Math.max(60, 15 * config.ratePerMinute()));
    }

    /** @return an immutable snapshot of the ledger. */
    public List<LedgerEntry> ledger() {
        return List.copyOf(ledger);
    }

    /** @return total scripts submitted across all generation windows (test observability). */
    int generatedCount() {
        return generatedCount.get();
    }

    // ------------------------------------------------------------- generation

    /**
     * Generates Poisson arrivals at the configured rate for {@code durationMinutes},
     * launching each workflow script on a virtual thread. Blocks for the
     * generation window; in-flight scripts continue afterwards.
     *
     * @param durationMinutes generation window
     */
    public void generate(int durationMinutes) {
        generateAt(config.ratePerMinute(), Duration.ofMinutes(durationMinutes), "chaos");
    }

    /**
     * Generates Poisson arrivals at an explicit rate for a bounded window —
     * used by the soak benchmark tail (design §6: steady low rate, chaos off).
     * Blocks for the window.
     *
     * @param ratePerMinute arrival rate
     * @param window        generation window
     * @param idPrefix      workflow-id prefix segment (distinguishes tail
     *                      workflows in the ledger and store)
     * @return the number of workflows submitted
     */
    public int generateAt(int ratePerMinute, Duration window, String idPrefix) {
        double lambdaPerSec = ratePerMinute / 60.0;
        long endNanos = System.nanoTime() + window.toNanos();
        // Belt-and-braces runaway guard (investigation §7.2): whatever the
        // trigger, generation may never exceed 3x the intended rate budget for
        // the window (+100 slack for tiny windows / Poisson variance).
        long runawayCap = 3 * Math.max(1,
                Math.round(ratePerMinute * (window.toMillis() / 60_000.0))) + 100;
        int seq = 0;
        log.info("[chaos] workload generation begins: {}/min for {} ({}, runaway cap {})",
                ratePerMinute, window, idPrefix, runawayCap);
        while (System.nanoTime() < endNanos) {
            double u = rng.nextDouble();
            long waitNanos = (long) (-Math.log(1 - u) / lambdaPerSec * 1_000_000_000L);
            pace(waitNanos, seq);
            if (System.nanoTime() >= endNanos) {
                break;
            }
            if (seq >= runawayCap) {
                log.error("[chaos] RUNAWAY GENERATION: seq {} exceeds 3x the intended "
                        + "budget for {}/min over {} — failing the run", seq, ratePerMinute, window);
                throw new IllegalStateException("Runaway workload generation: " + seq
                        + " scripts exceeds the 3x rate budget cap (" + runawayCap + ") for "
                        + ratePerMinute + "/min over " + window
                        + " — pacing is broken; failing the run loudly");
            }
            if (!acquireInFlightPermit(endNanos, seq)) {
                if (inFlight.availablePermits() == 0) {
                    log.warn("[chaos] generation back-pressured at the in-flight bound "
                            + "after {} scripts; window closed while waiting for a permit", seq);
                }
                break;   // window elapsed (possibly while back-pressured)
            }
            LoanPath path = LoanPath.pick(rng.nextInt(100));
            String appId = idPrefix + "-" + Long.toUnsignedString(config.seed()) + "-" + seq++;
            generatedCount.incrementAndGet();
            futures.add(executor.submit(() -> {
                try {
                    runScript(appId, path, true);
                } finally {
                    inFlight.release();
                }
            }));
        }
        log.info("[chaos] workload generation window closed: {} workflows submitted ({})",
                seq, idPrefix);
        return seq;
    }

    /**
     * Sleeps the Poisson inter-arrival gap. An interrupt aborts generation
     * LOUDLY: nothing in the harness legitimately interrupts mid-window, and a
     * swallowed interrupt previously degraded the pacer into a hot loop
     * (1.8M runaway submissions, both failed 2h soaks — investigation §5).
     */
    private void pace(long waitNanos, int seq) {
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                abortGeneration(seq, e);
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            abortGeneration(seq, null);   // pending interrupt / interrupted outside sleep
        }
    }

    /**
     * Acquires an in-flight permit, waiting no longer than the generation
     * window. An interrupt while waiting aborts generation loudly (same
     * contract as {@link #pace}).
     *
     * @return true if a permit was acquired; false if the window elapsed first
     */
    private boolean acquireInFlightPermit(long endNanos, int seq) {
        try {
            long remain = endNanos - System.nanoTime();
            return remain > 0 && inFlight.tryAcquire(remain, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abortGeneration(seq, e);
            return false;   // unreachable
        }
    }

    private void abortGeneration(int seq, /* nullable */ InterruptedException cause) {
        log.error("[chaos] GENERATION INTERRUPTED at seq {} on thread '{}' — aborting "
                + "generation and failing the run (a swallowed interrupt here caused the "
                + "soak-killing pacer runaway)", seq, Thread.currentThread().getName(), cause);
        throw new IllegalStateException("Workload generation interrupted at seq " + seq
                + " on thread '" + Thread.currentThread().getName()
                + "' — nothing in the harness legitimately interrupts mid-window; "
                + "failing the run loudly instead of hot-looping", cause);
    }

    /**
     * Runs a single path once, blocking until it reaches a terminal state
     * (GOLDEN calibration). Returns the workflow id.
     *
     * @param path  the path to run
     * @param index disambiguating index for the application id
     * @return the workflow id
     */
    public String runSingleBlocking(LoanPath path, int index) {
        String appId = "golden-" + path.name().toLowerCase() + "-" + index;
        runScript(appId, path, false);
        String workflowId = "loan-" + appId;
        awaitTerminal(Service.LOAN_APPLICATION, workflowId,
                config.sampleTimeout().plusSeconds(120));
        return workflowId;
    }

    /**
     * Waits for all launched scripts to finish, up to {@code deadline}.
     *
     * @param deadline upper bound
     */
    public void awaitScriptsSettled(Duration deadline) {
        long end = System.nanoTime() + deadline.toNanos();
        for (Future<?> f : futures) {
            long remain = end - System.nanoTime();
            if (remain <= 0) {
                break;
            }
            try {
                f.get(remain, TimeUnit.NANOSECONDS);
            } catch (Exception ignore) {
                // a stuck script surfaces at drain as an I1 finding, not here.
            }
        }
    }

    /** Shuts the virtual-thread executor down and closes the ledger writer. */
    public void close() {
        executor.shutdownNow();
        ledgerWriter.close();
    }

    // --------------------------------------------------------------- scripts

    private void runScript(String applicationId, LoanPath path, boolean generated) {
        String workflowId = "loan-" + applicationId;
        List<String> notes = new CopyOnWriteArrayList<>();
        String submittedAt = nowUtc();
        try {
            createApplication(applicationId, notes);
            uploadDoc(workflowId, applicationId, "income-proof", BORROWERS.get(0), notes);
            ensureUnderwritingRequested(applicationId, 1, notes);

            switch (path) {
                case HAPPY -> {
                    decide(applicationId, 1, "APPROVED", List.of(), notes);
                    signAll(workflowId, applicationId, notes);
                }
                case CONDITIONS_LOOP -> {
                    decide(applicationId, 1, "CONDITIONS", List.of("proof-of-insurance"), notes);
                    uploadDoc(workflowId, applicationId, "proof-of-insurance", BORROWERS.get(0), notes);
                    ensureUnderwritingRequested(applicationId, 2, notes);
                    decide(applicationId, 2, "APPROVED", List.of(), notes);
                    signAll(workflowId, applicationId, notes);
                }
                case SAGA_WITHDRAWAL -> {
                    decide(applicationId, 1, "APPROVED", List.of(), notes);
                    awaitRateLockReserved(applicationId, notes);
                    withdraw(workflowId, applicationId, notes);
                    signAll(workflowId, applicationId, notes);
                }
                case SIGNAL_TIMEOUT -> notes.add("no-decision-posted (deliberate SIGNAL_TIMEOUT)");
            }
        } catch (RuntimeException e) {
            notes.add("script-exception: " + e);
            log.warn("[chaos] script {} ({}) raised {}", workflowId, path, e.toString());
        } finally {
            var entry = new LedgerEntry(workflowId, applicationId, path, path.expectedTerminal(),
                    path.expectedOutput(), path.compensationExpected(), BORROWERS, submittedAt,
                    nowUtc(), List.copyOf(notes));
            ledger.add(entry);
            ledgerWriter.append(entry);
        }
    }

    private void createApplication(String applicationId, List<String> notes) {
        String body = String.format(
                "{\"applicationId\":\"%s\",\"borrowerIds\":[\"%s\",\"%s\"],\"amount\":%d,"
                + "\"income\":%d,\"propertyValue\":%d,\"requiredDocs\":[\"income-proof\"]}",
                applicationId, BORROWERS.get(0), BORROWERS.get(1), AMOUNT, INCOME, PROPERTY);
        String workflowId = "loan-" + applicationId;
        boolean ok = effectWithRepost(
                () -> post(Service.LOAN_APPLICATION, "/applications", body),
                () -> instanceExists(Service.LOAN_APPLICATION, workflowId),
                Duration.ofSeconds(15), config.sampleTimeout());
        if (!ok) {
            notes.add("create-not-confirmed");
        }
    }

    private void uploadDoc(String workflowId, String applicationId, String docType,
                           String uploadedBy, List<String> notes) {
        String body = String.format("{\"docType\":\"%s\",\"uploadedBy\":\"%s\"}", docType, uploadedBy);
        // Store-checked re-post keyed by docType (design §3: document re-posts
        // only after a store-level check that the first signal row never landed).
        boolean ok = effectWithRepost(
                () -> {
                    if (signalCountWithPayload(Service.LOAN_APPLICATION, workflowId,
                            "document.uploaded", "\"" + docType + "\"") == 0) {
                        post(Service.LOAN_APPLICATION,
                                "/applications/" + applicationId + "/documents", body);
                    }
                },
                () -> signalCountWithPayload(Service.LOAN_APPLICATION, workflowId,
                        "document.uploaded", "\"" + docType + "\"") >= 1,
                Duration.ofSeconds(20), config.sampleTimeout());
        if (!ok) {
            notes.add("doc-not-landed:" + docType);
        }
    }

    /**
     * Waits for the underwriting child workflow of {@code round} to exist.
     *
     * <p>No out-of-band verification fallback: verification workflows are
     * durable — a chaos-killed node's workflows are adopted and replayed, and
     * their {@code publishResult} activity is memoized — so a result is never
     * lost, only late. An earlier webhook fallback raced the real (late) Kafka
     * result and manufactured a near-duplicate signal row with different
     * payload bytes, which is exactly the {@code consumedTwin=false} shape I4
     * hard-fails on (triaged in evidence run 20260731-221119-203). Waiting the
     * full deadline is the honest driving strategy; a genuinely stuck
     * verification surfaces at drain as an I1 finding with this ledger note.
     */
    private void ensureUnderwritingRequested(String applicationId, int round, List<String> notes) {
        String childId = "underwriting-" + applicationId + "-round" + round;
        boolean requested = pollUntil(
                () -> instanceExists(Service.UNDERWRITING, childId),
                config.sampleTimeout(), Duration.ofSeconds(1));
        if (!requested) {
            notes.add("underwriting-round-" + round + "-not-requested");
        }
    }

    private void decide(String applicationId, int round, String verdict, List<String> conditions,
                        List<String> notes) {
        String conds = conditions.isEmpty() ? "[]"
                : "[" + String.join(",", conditions.stream().map(c -> "\"" + c + "\"").toList()) + "]";
        String body = String.format("{\"verdict\":\"%s\",\"conditions\":%s}", verdict, conds);
        String path = "/underwriting/" + applicationId + "/rounds/" + round + "/decision";
        String childId = "underwriting-" + applicationId + "-round" + round;
        // Store-checked re-post (I4 shaping): the durable decision signal lives
        // in the underwriting DB; once its row exists the workflow WILL consume
        // it (pre-arrived signals are consumed instantly), so re-posting after
        // that would only create an unconsumable duplicate. The effect awaited
        // is still the child reaching terminal.
        boolean ok = effectWithRepost(
                () -> {
                    if (signalCount(Service.UNDERWRITING, childId, "underwriter.decision") == 0) {
                        post(Service.UNDERWRITING, path, body);
                    }
                },
                () -> isTerminal(Service.UNDERWRITING, childId),
                Duration.ofSeconds(25), config.sampleTimeout());
        if (!ok) {
            notes.add("decision-round-" + round + "-" + verdict + "-not-consumed");
        }
    }

    private void signAll(String workflowId, String applicationId, List<String> notes) {
        for (String signer : BORROWERS) {
            // Store-checked re-post keyed by signerId: once this signer's row
            // exists it will be consumed by the signature fan-in (or deduped
            // while it is open); re-posting would only risk an unconsumable
            // duplicate after the fan-in closes (I4 shaping).
            boolean ok = effectWithRepost(
                    () -> {
                        if (signalCountWithPayload(Service.LOAN_APPLICATION, workflowId,
                                "package.signed", "\"" + signer + "\"") == 0) {
                            post(Service.LOAN_APPLICATION,
                                    "/applications/" + applicationId + "/sign",
                                    "{\"signerId\":\"" + signer + "\"}");
                        }
                    },
                    () -> signalCountWithPayload(Service.LOAN_APPLICATION, workflowId,
                            "package.signed", "\"" + signer + "\"") >= 1,
                    Duration.ofSeconds(20), config.sampleTimeout());
            if (!ok) {
                notes.add("signature-not-landed:" + signer);
            }
        }
    }

    private void withdraw(String workflowId, String applicationId, List<String> notes) {
        boolean ok = effectWithRepost(
                () -> {
                    if (signalCount(Service.LOAN_APPLICATION, workflowId, "application.withdrawn") == 0) {
                        post(Service.LOAN_APPLICATION,
                                "/applications/" + applicationId + "/withdraw",
                                "{\"reason\":\"chaos-saga-withdrawal\"}");
                    }
                },
                () -> signalCount(Service.LOAN_APPLICATION, workflowId, "application.withdrawn") >= 1,
                Duration.ofSeconds(20), config.sampleTimeout());
        if (!ok) {
            notes.add("withdrawal-not-landed");
        }
    }

    private void awaitRateLockReserved(String applicationId, List<String> notes) {
        // Boundary-checked id match: "chaos-...-1" must not match a
        // "chaos-...-10" line (the id is followed by a space in this message).
        var pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote("for loan " + applicationId) + "(?![\\w-])");
        // Incremental tail scan (soak-OOM fix, report §10): the old
        // implementation re-read EVERY loan log in full every second per
        // in-flight SAGA script — O(total log size) of humongous String
        // allocations per poll, growing without bound over a soak. The scanner
        // remembers a per-file offset and reads only bytes appended since the
        // last poll; total work is now O(log growth), not O(log size × polls).
        // Starting at the current end is correct: the reservation line can
        // only appear after this probe begins (the decision was just posted).
        var scanner = new LogTailScanner(() -> {
            var files = new ArrayList<java.nio.file.Path>();
            for (NodeRole role : List.of(NodeRole.LOAN_A, NodeRole.LOAN_B)) {
                files.addAll(cluster.logFiles(role));
            }
            return files;
        });
        boolean reserved = pollUntil(
                () -> scanner.newLinesMatch("Reserved rate lock", pattern),
                config.sampleTimeout(), Duration.ofSeconds(1));
        if (!reserved) {
            notes.add("rate-lock-reservation-not-observed");
        }
    }

    /**
     * Per-probe incremental scanner over a supplied set of log files (all
     * generations): tracks a byte offset per file and scans only newly
     * appended content on each call.
     *
     * <p>Offsets start at each file's length at first sight (see the caller's
     * rationale). Confined to the owning script's virtual thread. Static with
     * an injected file supplier so the offset semantics are unit-testable
     * without a cluster (delta-review Important #1).
     */
    static final class LogTailScanner {
        private static final int MAX_CHUNK = 4 * 1024 * 1024;

        private final java.util.function.Supplier<List<java.nio.file.Path>> files;
        private final java.util.Map<java.nio.file.Path, Long> offsets = new java.util.HashMap<>();
        private boolean primed;

        LogTailScanner(java.util.function.Supplier<List<java.nio.file.Path>> files) {
            this.files = files;
        }

        boolean newLinesMatch(String marker, java.util.regex.Pattern pattern) {
            boolean matched = false;
            for (var file : files.get()) {
                try {
                    if (!java.nio.file.Files.exists(file)) {
                        continue;
                    }
                    long size = java.nio.file.Files.size(file);
                    Long from = offsets.get(file);
                    if (from == null) {
                        // First sight: a file present before the probe
                        // started is skipped to its end on the priming
                        // pass; a file born later (container replacement)
                        // is read from 0.
                        from = primed ? 0L : size;
                        offsets.put(file, from);
                    }
                    if (size <= from) {
                        continue;
                    }
                    try (var raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
                        raf.seek(from);
                        byte[] chunk = new byte[(int) Math.min(size - from, MAX_CHUNK)];
                        int read = raf.read(chunk);
                        if (read > 0) {
                            // Standard tail semantics (delta-review Important
                            // #1): advance only past the last COMPLETE line —
                            // a partial trailing line (writer flush racing our
                            // size/read) is left in place and re-read whole on
                            // the next poll, never consumed in halves.
                            int lastNewline = -1;
                            for (int i = read - 1; i >= 0; i--) {
                                if (chunk[i] == '\n') {
                                    lastNewline = i;
                                    break;
                                }
                            }
                            if (lastNewline < 0) {
                                // No complete line yet. Escape hatch: a single
                                // line filling the whole 4MB cap can never
                                // complete — skip it rather than wedge.
                                if (read == MAX_CHUNK) {
                                    offsets.put(file, from + read);
                                }
                                continue;
                            }
                            offsets.put(file, from + lastNewline + 1);
                            String text = new String(chunk, 0, lastNewline,
                                    java.nio.charset.StandardCharsets.UTF_8);
                            for (String line : text.split("\n")) {
                                if (line.contains(marker) && pattern.matcher(line).find()) {
                                    matched = true;
                                }
                            }
                        }
                    }
                } catch (Exception ignore) {
                    // log not readable this cycle
                }
            }
            primed = true;
            return matched;
        }
    }

    // ---------------------------------------------------------- effect checks

    /**
     * Performs {@code action}, then waits until {@code effect} holds, re-posting
     * {@code action} every {@code repostAfter} until the ultimate {@code deadline}.
     * Workload logic (not an assertion): the design mandates bounded re-posts for
     * Kafka-riding actions whose consequence may be skipped mid-rebalance.
     */
    private boolean effectWithRepost(Runnable action, BooleanSupplier effect,
                                     Duration repostAfter, Duration deadline) {
        long end = System.nanoTime() + deadline.toNanos();
        action.run();
        long lastPost = System.nanoTime();
        while (System.nanoTime() < end) {
            if (safe(effect)) {
                return true;
            }
            if (System.nanoTime() - lastPost > repostAfter.toNanos()) {
                action.run();
                lastPost = System.nanoTime();
            }
            if (!parkNanos(Duration.ofMillis(500).toNanos())) {
                break;   // interrupted (shutdown): stop waiting, final check below
            }
        }
        return safe(effect);
    }

    private boolean pollUntil(BooleanSupplier cond, Duration deadline, Duration interval) {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (safe(cond)) {
                return true;
            }
            if (!parkNanos(interval.toNanos())) {
                break;   // interrupted (shutdown): stop waiting, final check below
            }
        }
        return safe(cond);
    }

    private boolean safe(BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void awaitTerminal(Service svc, String workflowId, Duration deadline) {
        pollUntil(() -> isTerminal(svc, workflowId), deadline, Duration.ofSeconds(1));
    }

    // ------------------------------------------------------------------- HTTP

    /**
     * POSTs {@code body} to {@code path} on a live node of {@code svc}, trying
     * the service's <em>non-harassed</em> node roles until one accepts (status
     * &lt; 300) or a short deadline elapses. Never throws.
     *
     * <p>Harassed nodes (paused, partitioned, killed, stopping) are skipped
     * deliberately — this is the live-endpoint-registry routing the design
     * mandates, and it matters for correctness of the I4 invariant: a request
     * sent to a <em>paused</em> node queues in its socket backlog and replays
     * when the node is unpaused, delivering a late duplicate signal after the
     * workflow's fan-in has closed. Routing around harassed nodes (as a
     * health-checked load balancer would) removes that over-delivery mechanism
     * at its source.
     */
    private void post(Service svc, String path, String body) {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int attempt = 0;
        while (System.nanoTime() < end) {
            var harassed = cluster.harassedRoles();
            for (NodeRole role : svc.roles()) {
                if (harassed.contains(role)) {
                    continue;   // never queue a request on a frozen/partitioned node
                }
                String base = baseUrlOrNull(role);
                if (base == null) {
                    continue;
                }
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path))
                                    .timeout(Duration.ofSeconds(3))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() < 300) {
                        return;
                    }
                } catch (Exception ignore) {
                    // try the peer node
                }
            }
            if (!parkNanos(Duration.ofMillis(300 + 100L * attempt++).toNanos())) {
                return;   // interrupted (shutdown): give up the post
            }
        }
    }

    private String baseUrlOrNull(NodeRole role) {
        try {
            return cluster.baseUrl(role);
        } catch (RuntimeException e) {
            return null;   // container replaced / not yet live
        }
    }

    // --------------------------------------------------------------- store IO

    private boolean instanceExists(Service svc, String workflowId) {
        return instanceStatus(svc, workflowId).isPresent();
    }

    private boolean isTerminal(Service svc, String workflowId) {
        return instanceStatus(svc, workflowId)
                .map(s -> s.equals("COMPLETED") || s.equals("FAILED") || s.equals("TERMINATED"))
                .orElse(false);
    }

    private Optional<String> instanceStatus(Service svc, String workflowId) {
        String sql = "SELECT status FROM maestro_workflow_instance WHERE workflow_id = ?";
        try (var c = cluster.dataSource(svc).getConnection();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private long signalCount(Service svc, String workflowId, String signalName) {
        String sql = "SELECT COUNT(*) FROM maestro_workflow_signal "
                + "WHERE workflow_id = ? AND signal_name = ?";
        try (var c = cluster.dataSource(svc).getConnection();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setString(2, signalName);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Signal rows for {@code workflowId}/{@code signalName} whose payload contains {@code needle}. */
    private long signalCountWithPayload(Service svc, String workflowId, String signalName, String needle) {
        String sql = "SELECT COUNT(*) FROM maestro_workflow_signal "
                + "WHERE workflow_id = ? AND signal_name = ? AND payload::text LIKE ?";
        try (var c = cluster.dataSource(svc).getConnection();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setString(2, signalName);
            ps.setString(3, "%" + needle + "%");
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Reads {@code output->>'status'} for a completed workflow, if present. */
    Optional<String> outputStatus(Service svc, String workflowId) {
        String sql = "SELECT output FROM maestro_workflow_instance WHERE workflow_id = ?";
        try (var c = cluster.dataSource(svc).getConnection();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            try (var rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null) {
                    JsonNode node = mapper.readTree(rs.getString(1));
                    JsonNode status = node.get("status");
                    return status == null ? Optional.empty() : Optional.of(status.asString());
                }
            }
        } catch (Exception ignore) {
            // absent / unparseable
        }
        return Optional.empty();
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * Script-side sleep. Returns {@code false} if the thread is interrupted
     * (executor shutdown), re-asserting the flag — callers must then abort
     * their wait loop instead of hot-spinning on a no-op sleep (the swallowed
     * interrupt that destroyed the pacer also made interrupted scripts burn
     * CPU until their deadlines).
     */
    private static boolean parkNanos(long nanos) {
        if (nanos <= 0) {
            return !Thread.currentThread().isInterrupted();
        }
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
