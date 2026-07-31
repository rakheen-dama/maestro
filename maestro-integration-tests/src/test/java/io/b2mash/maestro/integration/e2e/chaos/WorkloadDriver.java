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
    private static final List<String> VERIFICATION_TYPES = List.of("credit", "employment", "appraisal");

    private final ChaosCluster cluster;
    private final ChaosConfig config;
    private final SplittableRandom rng;
    private final EvidenceWriter.JsonlWriter ledgerWriter;
    private final List<LedgerEntry> ledger = new CopyOnWriteArrayList<>();
    private final List<Future<?>> futures = new CopyOnWriteArrayList<>();
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
    }

    /** @return an immutable snapshot of the ledger. */
    public List<LedgerEntry> ledger() {
        return List.copyOf(ledger);
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
        double lambdaPerSec = config.ratePerMinute() / 60.0;
        long endNanos = System.nanoTime() + Duration.ofMinutes(durationMinutes).toNanos();
        int seq = 0;
        log.info("[chaos] workload generation begins: {}/min for {} min",
                config.ratePerMinute(), durationMinutes);
        while (System.nanoTime() < endNanos) {
            double u = rng.nextDouble();
            long waitNanos = (long) (-Math.log(1 - u) / lambdaPerSec * 1_000_000_000L);
            parkNanos(waitNanos);
            if (System.nanoTime() >= endNanos) {
                break;
            }
            LoanPath path = LoanPath.pick(rng.nextInt(100));
            String appId = appId(seq++);
            futures.add(executor.submit(() -> runScript(appId, path, true)));
        }
        log.info("[chaos] workload generation window closed: {} workflows submitted", seq);
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
            uploadDoc(workflowId, applicationId, "income-proof", BORROWERS.get(0), 1, notes);
            ensureUnderwritingRequested(applicationId, 1, notes);

            switch (path) {
                case HAPPY -> {
                    decide(applicationId, 1, "APPROVED", List.of(), notes);
                    signAll(workflowId, applicationId, notes);
                }
                case CONDITIONS_LOOP -> {
                    decide(applicationId, 1, "CONDITIONS", List.of("proof-of-insurance"), notes);
                    uploadDoc(workflowId, applicationId, "proof-of-insurance", BORROWERS.get(0), 2, notes);
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
                           String uploadedBy, int expectedTotal, List<String> notes) {
        String body = String.format("{\"docType\":\"%s\",\"uploadedBy\":\"%s\"}", docType, uploadedBy);
        boolean ok = effectWithRepost(
                () -> post(Service.LOAN_APPLICATION, "/applications/" + applicationId + "/documents", body),
                () -> signalCount(Service.LOAN_APPLICATION, workflowId, "document.uploaded") >= expectedTotal,
                Duration.ofSeconds(20), config.sampleTimeout());
        if (!ok) {
            notes.add("doc-not-landed:" + docType);
        }
    }

    private void ensureUnderwritingRequested(String applicationId, int round, List<String> notes) {
        String childId = "underwriting-" + applicationId + "-round" + round;
        boolean requested = pollUntil(
                () -> instanceExists(Service.UNDERWRITING, childId),
                Duration.ofSeconds(25), Duration.ofSeconds(1));
        if (!requested && round == 1) {
            // Verifications may be stalled (verify nodes harassed / rebalance skip).
            // Deliver the three results out-of-band via the webhook (approved),
            // which the loan workflow dedupes by type (Risk 1 mitigation).
            notes.add("verification-webhook-fallback");
            for (String type : VERIFICATION_TYPES) {
                post(Service.VERIFICATION_GATEWAY,
                        "/webhooks/" + type + "/" + applicationId, "{\"approved\":true}");
            }
            requested = pollUntil(() -> instanceExists(Service.UNDERWRITING, childId),
                    config.sampleTimeout(), Duration.ofSeconds(1));
        }
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
        boolean ok = effectWithRepost(
                () -> post(Service.UNDERWRITING, path, body),
                () -> isTerminal(Service.UNDERWRITING, childId),
                Duration.ofSeconds(25), config.sampleTimeout());
        if (!ok) {
            notes.add("decision-round-" + round + "-" + verdict + "-not-consumed");
        }
    }

    private void signAll(String workflowId, String applicationId, List<String> notes) {
        for (int i = 0; i < BORROWERS.size(); i++) {
            String signer = BORROWERS.get(i);
            int expected = i + 1;
            boolean ok = effectWithRepost(
                    () -> post(Service.LOAN_APPLICATION,
                            "/applications/" + applicationId + "/sign",
                            "{\"signerId\":\"" + signer + "\"}"),
                    () -> signalCount(Service.LOAN_APPLICATION, workflowId, "package.signed") >= expected,
                    Duration.ofSeconds(20), config.sampleTimeout());
            if (!ok) {
                notes.add("signature-not-landed:" + signer);
            }
        }
    }

    private void withdraw(String workflowId, String applicationId, List<String> notes) {
        boolean ok = effectWithRepost(
                () -> post(Service.LOAN_APPLICATION,
                        "/applications/" + applicationId + "/withdraw",
                        "{\"reason\":\"chaos-saga-withdrawal\"}"),
                () -> signalCount(Service.LOAN_APPLICATION, workflowId, "application.withdrawn") >= 1,
                Duration.ofSeconds(20), config.sampleTimeout());
        if (!ok) {
            notes.add("withdrawal-not-landed");
        }
    }

    private void awaitRateLockReserved(String applicationId, List<String> notes) {
        boolean reserved = pollUntil(
                () -> logsContain("Reserved rate lock", "for loan " + applicationId),
                config.sampleTimeout(), Duration.ofSeconds(1));
        if (!reserved) {
            notes.add("rate-lock-reservation-not-observed");
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
            parkNanos(Duration.ofMillis(500).toNanos());
        }
        return safe(effect);
    }

    private boolean pollUntil(BooleanSupplier cond, Duration deadline, Duration interval) {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (safe(cond)) {
                return true;
            }
            parkNanos(interval.toNanos());
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
     * both node roles until one accepts (status &lt; 300) or a short deadline
     * elapses. Never throws.
     */
    private void post(Service svc, String path, String body) {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int attempt = 0;
        while (System.nanoTime() < end) {
            for (NodeRole role : svc.roles()) {
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
            parkNanos(Duration.ofMillis(300 + 100L * attempt++).toNanos());
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

    private boolean logsContain(String... needles) {
        for (NodeRole role : List.of(NodeRole.LOAN_A, NodeRole.LOAN_B)) {
            for (var file : cluster.logFiles(role)) {
                try {
                    if (!java.nio.file.Files.exists(file)) {
                        continue;
                    }
                    String content = java.nio.file.Files.readString(file);
                    for (String line : content.split("\n")) {
                        boolean all = true;
                        for (String n : needles) {
                            if (!line.contains(n)) {
                                all = false;
                                break;
                            }
                        }
                        if (all) {
                            return true;
                        }
                    }
                } catch (Exception ignore) {
                    // log not readable yet
                }
            }
        }
        return false;
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

    private String appId(int seq) {
        return "chaos-" + Long.toUnsignedString(config.seed()) + "-" + seq;
    }

    private static String nowUtc() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private static void parkNanos(long nanos) {
        if (nanos <= 0) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
