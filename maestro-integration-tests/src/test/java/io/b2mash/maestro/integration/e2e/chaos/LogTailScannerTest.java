package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED pin for delta-review Important #1: the incremental tail scanner must not
 * advance its offset past a partial trailing line. A "Reserved rate lock ...
 * for loan <id>" line split across two polls (log writer flush vs concurrent
 * size/read is not atomic; docker frames need not end on a newline) must be
 * re-read complete on the next poll — the pre-fix scanner consumed both halves
 * forever, silently burning the SAGA probe's full sample timeout.
 */
class LogTailScannerTest {

    private static final String MARKER = "Reserved rate lock";
    private static final Pattern ID_PATTERN =
            Pattern.compile(Pattern.quote("for loan chaos-42-7") + "(?![\\w-])");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("effect line split across two polls is still matched once complete")
    void splitTrailingLine_isRereadCompleteOnNextPoll() throws Exception {
        Path log = tmp.resolve("loan-a-gen1.log");
        Files.writeString(log, "boot noise line\n");
        var scanner = new WorkloadDriver.LogTailScanner(() -> List.of(log));

        // Priming pass: pre-existing content is skipped to EOF by design.
        assertFalse(scanner.newLinesMatch(MARKER, ID_PATTERN));

        // The writer lands only the first half of the effect line (no newline yet).
        Files.writeString(log, "12:00:00.001 INFO Reserved rate l",
                StandardOpenOption.APPEND);
        assertFalse(scanner.newLinesMatch(MARKER, ID_PATTERN),
                "half a line must not match");

        // The rest of the line lands before the next poll.
        Files.writeString(log, "ock at 5.1% for loan chaos-42-7 (chaos)\n",
                StandardOpenOption.APPEND);
        assertTrue(scanner.newLinesMatch(MARKER, ID_PATTERN),
                "the completed effect line was permanently missed: the offset "
                + "advanced past the partial trailing line on the previous poll");
    }

    @Test
    @DisplayName("boundary-checked id match still works on whole lines across polls")
    void wholeLines_matchAcrossPolls() throws Exception {
        Path log = tmp.resolve("loan-b-gen1.log");
        Files.writeString(log, "");
        var scanner = new WorkloadDriver.LogTailScanner(() -> List.of(log));
        assertFalse(scanner.newLinesMatch(MARKER, ID_PATTERN));

        Files.writeString(log, "x Reserved rate lock at 5.1% for loan chaos-42-70 (no)\n",
                StandardOpenOption.APPEND);
        assertFalse(scanner.newLinesMatch(MARKER, ID_PATTERN),
                "chaos-42-70 must not satisfy the chaos-42-7 boundary-checked match");

        Files.writeString(log, "x Reserved rate lock at 5.1% for loan chaos-42-7 (yes)\n",
                StandardOpenOption.APPEND);
        assertTrue(scanner.newLinesMatch(MARKER, ID_PATTERN));
    }
}
