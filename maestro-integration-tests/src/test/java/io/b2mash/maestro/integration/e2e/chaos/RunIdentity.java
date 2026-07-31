package io.b2mash.maestro.integration.e2e.chaos;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Artifact-identity header stamped into every evidence file the harness writes
 * (chaos-harness-design.md §9; {@code tasks/lessons.md}, binding). QA rejects
 * an artifact by its <em>embedded</em> identity, never by filename or recency,
 * so every JSONL/CSV file carries this as its first line.
 *
 * <p>Captures the working directory, the exact {@code git} commit and branch,
 * the run's UTC start timestamp, the master seed, the run mode and the run id.
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable record; safe to share.
 *
 * @param pwd          absolute working directory of the test JVM
 * @param gitHead      {@code git rev-parse HEAD}
 * @param branch       {@code git rev-parse --abbrev-ref HEAD}
 * @param timestampUtc run start, UTC ISO-8601
 * @param seed         master PRNG seed
 * @param mode         run mode
 * @param runId        per-run directory name {@code <yyyyMMdd-HHmmss>-<seed>}
 */
public record RunIdentity(
        String pwd,
        String gitHead,
        String branch,
        String timestampUtc,
        long seed,
        ChaosMode mode,
        String runId) {

    private static final DateTimeFormatter RUN_ID_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /**
     * Captures the current identity at run start.
     *
     * @param seed the master seed
     * @param mode the run mode
     * @return the populated identity
     */
    public static RunIdentity capture(long seed, ChaosMode mode) {
        Instant now = Instant.now();
        String runId = RUN_ID_FMT.format(now) + "-" + seed;
        return new RunIdentity(
                System.getProperty("user.dir"),
                git("rev-parse", "HEAD"),
                git("rev-parse", "--abbrev-ref", "HEAD"),
                DateTimeFormatter.ISO_INSTANT.format(now),
                seed,
                mode,
                runId);
    }

    private static String git(String... args) {
        try {
            var cmd = new java.util.ArrayList<String>();
            cmd.add("git");
            cmd.addAll(java.util.List.of(args));
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.isBlank() ? "unknown" : out;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
