-- Scenario 2 (s2-recovery-drain): the adoption compare-and-set, counted in
-- the current pg_stat_statements window. Run right after pgss-window.sql and
-- s3-components.sql (which already classify lockAcquire, recoveryPage and
-- heldKeys). Top-level engine entries, full text, whitespace collapsed.
--
-- adoptCas is WorkflowExecutor.adopt (maestro-core WorkflowExecutor.java:2372-2380):
-- store.updateInstance(adopted) with no RunLease, i.e. the UNFENCED form of
-- AbstractJdbcWorkflowStore.updateInstanceSql (maestro-store-jdbc
-- AbstractJdbcWorkflowStore.java:673-685, the same on fix/recovery-capacity
-- 14616968: WP6 does not touch the store), which pg_stat_statements records as
--   UPDATE maestro_workflow_instance SET run_id = $1, workflow_type = $2, ...,
--   version = $12, lease_epoch = $13 WHERE id = $14 AND version = $15
-- The new epoch is a bind parameter (lease_epoch = $13), not
-- "lease_epoch = lease_epoch + 1" as the WP12 plan assumed. The FENCED form
-- (a run's own writes under its RunLease) ends "... AND lease_epoch = $16"
-- and is not matched. The other unfenced callers (terminate,
-- WorkflowStore.reopen for retry, a status write without a lease) do not run
-- in a recovery drain of chain workflows, so in Scenario 2's windows this
-- text is the adoption CAS. Runs as postgres.
WITH engine AS (
    SELECT calls, btrim(regexp_replace(query, '\s+', ' ', 'g')) AS q
    FROM pg_stat_statements
    WHERE userid = 'maestro'::regrole AND toplevel
)
SELECT json_build_object(
    'adoptCas', json_build_object(
        'calls', (SELECT coalesce(sum(calls), 0) FROM engine
                  WHERE q ~ '^UPDATE maestro_workflow_instance SET run_id = .* WHERE id = \$[0-9]+ AND version = \$[0-9]+$'),
        'entries', (SELECT count(*) FROM engine
                    WHERE q ~ '^UPDATE maestro_workflow_instance SET run_id = .* WHERE id = \$[0-9]+ AND version = \$[0-9]+$')),
    'fencedUpdate', json_build_object(
        'calls', (SELECT coalesce(sum(calls), 0) FROM engine
                  WHERE q ~ '^UPDATE maestro_workflow_instance SET run_id = .* AND lease_epoch = \$[0-9]+$')));
