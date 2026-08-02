package io.b2mash.maestro.integration.e2e.chaos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the checker-blindness contract (CodeRabbit wave, PR #30): a store that
 * is unreachable when the <em>authoritative</em> verify runs must produce a
 * hard violation — never an empty list that the caller reads as PASS. The
 * <em>periodic</em> path stays soft: its probe-based blindness accounting
 * ({@link PeriodicChecker}) already counts and escalates unreachable cycles,
 * and a periodic cycle must keep checking whatever it still can reach.
 */
class InvariantCheckerBlindnessTest {

    @TempDir
    Path tmp;

    private InvariantChecker checkerAgainstDeadStore() throws SQLException {
        ChaosCluster cluster = mock(ChaosCluster.class);
        PGSimpleDataSource dead = mock(PGSimpleDataSource.class);
        when(dead.getConnection()).thenThrow(new SQLException("connection refused (store down)"));
        when(cluster.dataSource(any())).thenReturn(dead);
        var identity = new RunIdentity(tmp.toString(), "test", "test",
                "2026-08-02T00:00:00Z", 1L, ChaosMode.PR_GATE, "test-run");
        var evidence = new EvidenceWriter(identity, tmp);
        return new InvariantChecker(cluster, evidence, List.of());
    }

    @Test
    void authoritativeVerifyAgainstUnreachableStoreIsAHardFailureNotAPass() throws SQLException {
        var result = checkerAgainstDeadStore().verifyAuthoritative();

        assertFalse(result.violations().isEmpty(),
                "an unreachable store at authoritative-verify time must FAIL the run — "
                        + "an empty violation list here is a blind PASS");
        assertTrue(result.violations().stream()
                        .allMatch(v -> v.detail().contains("BLIND")),
                "each violation must say the checker was blind, naming the failed query");
    }

    @Test
    void periodicChecksStaySoftWhenStoreUnreachable() throws SQLException {
        var checker = checkerAgainstDeadStore();

        assertTrue(checker.checkAlwaysInexcusable().isEmpty(),
                "periodic path keeps the soft contract — PeriodicChecker's own "
                        + "probe accounting reports the outage");
        assertTrue(checker.checkStuckWaitingTimer().isEmpty(),
                "periodic path keeps the soft contract");
    }
}
