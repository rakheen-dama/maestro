package io.b2mash.maestro.rollingnode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the line format {@code RollingUpgradeIT} parses ({@code RollingNode.Event.parse}):
 * five {@code |}-separated fields on one line, whatever the detail holds.
 */
class RollingObserverTest {

    @Test
    void aCallbackIsOneLineOfFiveFields() {
        var line = RollingObserver.line("standDown", "wf-1", "NONDETERMINISTIC_HISTORY", "seq=2 expected=a stored=b");

        assertEquals("ROLLING-EVENT|standDown|wf-1|NONDETERMINISTIC_HISTORY|seq=2 expected=a stored=b", line);
    }

    @Test
    void aDetailWithLineBreaksOrSeparatorsStaysInItsField() {
        var line = RollingObserver.line("standDown", "wf-1", "UNKNOWN_EVENT_PAYLOAD", "a|b\nc\r\nd");

        assertEquals(1, line.lines().count());
        assertEquals(5, line.split("\\|", -1).length);
        assertEquals("ROLLING-EVENT|standDown|wf-1|UNKNOWN_EVENT_PAYLOAD|a/b c  d", line);
    }

    @Test
    void noDetailLeavesTheLastFieldEmpty() {
        assertEquals("ROLLING-EVENT|runAbandoned|wf-1|SHUTDOWN|", RollingObserver.line("runAbandoned", "wf-1", "SHUTDOWN", null));
    }
}
