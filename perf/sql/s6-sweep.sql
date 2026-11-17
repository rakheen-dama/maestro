-- Scenario 6 (s6-retention): the retention purge and the signal janitor in
-- the current pg_stat_statements window (run right after pgss-window.sql),
-- engine role, top-level entries, full text with whitespace collapsed:
--   purgeCandidates  SELECT id FROM maestro_workflow_instance WHERE status = $1
--                    AND completed_at < ... (one per batch, plus one short
--                    read per status that ends its loop)
--   purgeDeletes     DELETE FROM maestro_workflow_(signal|timer|event) WHERE
--                    workflow_instance_id IN (...) and DELETE FROM
--                    maestro_workflow_instance WHERE id IN (...) (four per
--                    batch; AbstractJdbcWorkflowStore.java:1201-1208)
--   janitor          DELETE FROM maestro_workflow_signal WHERE consumed = ...
--                    (AbstractJdbcWorkflowStore.java:1653-1657; the
--                    pgss-window.sql signalJanitor pattern). A batched janitor
--                    whose text carries "... AND id IN (" is still janitor
--                    work here, never purge work: the purge patterns are
--                    anchored on "WHERE workflow_instance_id IN (" and
--                    "maestro_workflow_instance WHERE id IN (".
-- Each: {calls, rows, totalMs, entries}. And walBytes: pg_stat_wal.wal_bytes
-- now (the scenario takes the difference). Runs as postgres.
WITH e AS (
    SELECT calls, rows, total_exec_time, btrim(regexp_replace(query, '\s+', ' ', 'g')) AS q
    FROM pg_stat_statements
    WHERE userid = 'maestro'::regrole AND toplevel
), c AS (
    SELECT calls, rows, total_exec_time,
        CASE
            WHEN q ~ '^SELECT id FROM maestro_workflow_instance WHERE status = \$[0-9]+ AND completed_at < ' THEN 'purgeCandidates'
            WHEN q ~ '^DELETE FROM maestro_workflow_(signal|timer|event) WHERE workflow_instance_id IN \(' THEN 'purgeDeletes'
            WHEN q ~ '^DELETE FROM maestro_workflow_instance WHERE id IN \(' THEN 'purgeDeletes'
            WHEN q ~ '^DELETE FROM maestro_workflow_signal WHERE consumed = ' THEN 'janitor'
            ELSE NULL
        END AS k
    FROM e
)
SELECT json_build_object(
    'components', (SELECT coalesce(json_object_agg(k, json_build_object('calls', calls, 'rows', rows,
                       'totalMs', round(ms::numeric, 3), 'entries', entries)), '{}'::json)
                   FROM (SELECT k, sum(calls) AS calls, sum(rows) AS rows, sum(total_exec_time) AS ms, count(*) AS entries
                         FROM c WHERE k IS NOT NULL GROUP BY k) g),
    'walBytes', (SELECT wal_bytes FROM pg_stat_wal),
    'at', now());
