package io.b2mash.maestro.integration.e2e.chaos;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns a single run's evidence directory and writes every artifact with the
 * mandatory identity header (chaos-harness-design.md §9). The directory is
 * {@code <evidenceRoot>/<runId>}; nothing is pruned automatically.
 *
 * <p>JSONL artifacts (action log, ledger, per-workflow dumps) carry the
 * {@link RunIdentity} as their first line; CSV artifacts (metrics) carry it as
 * a {@code #}-prefixed first line. Every append is flushed immediately so a
 * crashed run still leaves complete evidence on disk.
 *
 * <h2>Thread Safety</h2>
 * <p>The writer factory is safe for concurrent use. Each {@link JsonlWriter} /
 * {@link CsvWriter} serialises its own appends under an intrinsic lock, so a
 * single artifact may be written from several threads.
 */
public final class EvidenceWriter {

    private final RunIdentity identity;
    private final Path runDir;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Map<String, Object> identityMap;

    /**
     * Creates the run directory and captures the identity to stamp.
     *
     * @param identity      the run identity
     * @param evidenceRoot  parent directory for per-run directories
     */
    public EvidenceWriter(RunIdentity identity, Path evidenceRoot) {
        this.identity = identity;
        this.runDir = evidenceRoot.resolve(identity.runId());
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create evidence dir " + runDir, e);
        }
        this.identityMap = identityMap(identity);
    }

    private static Map<String, Object> identityMap(RunIdentity id) {
        var m = new LinkedHashMap<String, Object>();
        m.put("pwd", id.pwd());
        m.put("gitHead", id.gitHead());
        m.put("branch", id.branch());
        m.put("timestampUtc", id.timestampUtc());
        m.put("seed", id.seed());
        m.put("mode", id.mode().name());
        m.put("runId", id.runId());
        return m;
    }

    /** @return the run's evidence directory. */
    public Path runDir() {
        return runDir;
    }

    /** @return the run identity. */
    public RunIdentity identity() {
        return identity;
    }

    /** @return the shared Jackson 3 mapper. */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Opens (truncating) a JSONL artifact and writes the identity header line.
     *
     * @param fileName artifact file name relative to the run dir
     * @return an append-only, per-line-flushed writer
     */
    public JsonlWriter openJsonl(String fileName) {
        Path file = runDir.resolve(fileName);
        try {
            Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            JsonlWriter jw = new JsonlWriter(w, mapper);
            jw.append(Map.of("_identity", identityMap));
            return jw;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open JSONL " + file, e);
        }
    }

    /**
     * Opens (truncating) a CSV artifact, writes the {@code #}-identity header and
     * the column header row.
     *
     * @param fileName  artifact file name relative to the run dir
     * @param headerRow the CSV column header
     * @return an append-only, per-line-flushed writer
     */
    public CsvWriter openCsv(String fileName, String headerRow) {
        Path file = runDir.resolve(fileName);
        try {
            Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            w.write("# " + mapper.writeValueAsString(identityMap) + "\n");
            w.write(headerRow + "\n");
            w.flush();
            return new CsvWriter(w);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open CSV " + file, e);
        }
    }

    /**
     * Writes a pretty-printed JSON dump file carrying the identity under
     * {@code _identity} (used for failure dumps, the side-effect census and the
     * calibration record).
     *
     * @param relativePath path relative to the run dir (parents created)
     * @param payload      the object graph to serialise
     */
    public void writeJson(String relativePath, Object payload) {
        Path file = runDir.resolve(relativePath);
        try {
            Files.createDirectories(file.getParent());
            var wrapped = new LinkedHashMap<String, Object>();
            wrapped.put("_identity", identityMap);
            wrapped.put("payload", payload);
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write JSON dump " + file, e);
        }
    }

    /**
     * Writes a plain UTF-8 text artifact with a {@code #}-identity header
     * (used for cross-node log excerpts in failure dumps).
     *
     * @param relativePath path relative to the run dir (parents created)
     * @param body         the text body
     */
    public void writeText(String relativePath, String body) {
        Path file = runDir.resolve(relativePath);
        try {
            Files.createDirectories(file.getParent());
            String header;
            try {
                header = "# " + mapper.writeValueAsString(identityMap) + "\n";
            } catch (Exception e) {
                header = "# identity-serialization-failed\n";
            }
            Files.writeString(file, header + body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write text " + file, e);
        }
    }

    /** Append-only JSONL writer; one JSON object per line, flushed per append. */
    public static final class JsonlWriter implements AutoCloseable {
        private final Writer w;
        private final ObjectMapper mapper;

        private JsonlWriter(Writer w, ObjectMapper mapper) {
            this.w = w;
            this.mapper = mapper;
        }

        /**
         * Serialises and appends {@code record} as one line, then flushes.
         *
         * @param record the object to serialise
         */
        public synchronized void append(Object record) {
            try {
                w.write(mapper.writeValueAsString(record));
                w.write('\n');
                w.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("JSONL append failed", e);
            }
        }

        @Override
        public synchronized void close() {
            try {
                w.close();
            } catch (IOException e) {
                throw new UncheckedIOException("JSONL close failed", e);
            }
        }
    }

    /** Append-only CSV writer; one row per line, flushed per append. */
    public static final class CsvWriter implements AutoCloseable {
        private final Writer w;

        private CsvWriter(Writer w) {
            this.w = w;
        }

        /**
         * Appends a CSV row (no trailing newline needed), then flushes.
         *
         * @param row the comma-joined row
         */
        public synchronized void append(String row) {
            try {
                w.write(row);
                w.write('\n');
                w.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("CSV append failed", e);
            }
        }

        @Override
        public synchronized void close() {
            try {
                w.close();
            } catch (IOException e) {
                throw new UncheckedIOException("CSV close failed", e);
            }
        }
    }
}
