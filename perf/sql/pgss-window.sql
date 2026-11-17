-- Closes a pg_stat_statements measurement window opened by pgss-reset.sql.
-- maestroCalls counts only the engine's role; everything else (this harness
-- runs as postgres) is harnessCalls and never measured. With
-- pg_stat_statements.track=all, maestroCalls also counts statements nested
-- inside others (foreign-key trigger queries); maestroCallsTopLevel counts
-- only what the engine itself sent. maestroStatements is the number of
-- distinct engine statement entries. dealloc > 0 means entries were evicted
-- and the counts are incomplete. Runs as postgres.
--
-- sweepCalls classifies the engine's periodic sweeps over EVERY engine entry
-- (not only the top 25), top-level calls only, by statement text (whitespace
-- collapsed, constants normalised to $n by pg_stat_statements):
--   lockCleaner    PostgresLockCleaner: DELETE FROM maestro_distributed_lock /
--                  maestro_leader_election WHERE expires_at < now() ... (two
--                  statements per pass, one call each; the lock release is
--                  "WHERE lock_key = ...", never matched)
--   signalJanitor  DELETE FROM maestro_workflow_signal WHERE consumed = ...
--                  (deleteUnconsumedSignalsForTerminalInstancesBefore)
--   retention      SELECT id FROM maestro_workflow_instance WHERE status = $n
--                  AND completed_at < ... (the retention sweep's candidate
--                  select, issued per status on every sweep)
-- perf/eval/common.jq (sweeps_check, statement_figures) accounts for them.
WITH info AS (
    SELECT stats_reset, dealloc FROM pg_stat_statements_info
), engine AS (
    SELECT toplevel, calls, rows, total_exec_time, query
    FROM pg_stat_statements
    WHERE userid = 'maestro'::regrole
), engine_top AS (
    SELECT calls, btrim(regexp_replace(query, '\s+', ' ', 'g')) AS q
    FROM engine
    WHERE toplevel
), sweeps AS (
    SELECT
        coalesce(sum(calls) FILTER (WHERE q ~ '^DELETE FROM maestro_(distributed_lock|leader_election) WHERE expires_at < now\(\)'), 0) AS lock_cleaner,
        count(*) FILTER (WHERE q ~ '^DELETE FROM maestro_(distributed_lock|leader_election) WHERE expires_at < now\(\)') AS lock_cleaner_statements,
        coalesce(sum(calls) FILTER (WHERE q ~ '^DELETE FROM maestro_workflow_signal WHERE consumed = '), 0) AS signal_janitor,
        coalesce(sum(calls) FILTER (WHERE q ~ '^SELECT id FROM maestro_workflow_instance WHERE status = \$[0-9]+ AND completed_at < '), 0) AS retention
    FROM engine_top
), top AS (
    SELECT toplevel, calls, rows, total_exec_time, query
    FROM engine
    ORDER BY calls DESC, total_exec_time DESC
    LIMIT 25
)
SELECT json_build_object(
    'resetAt', info.stats_reset,
    'snapshotAt', now(),
    'windowSecs', round(extract(epoch FROM now() - info.stats_reset)::numeric, 3),
    'dealloc', info.dealloc,
    'maestroCalls', (SELECT coalesce(sum(calls), 0) FROM engine),
    'maestroCallsTopLevel', (SELECT coalesce(sum(calls), 0) FROM engine WHERE toplevel),
    'maestroStatements', (SELECT count(*) FROM engine),
    'sweepCalls', (SELECT json_build_object(
                       'lockCleaner', lock_cleaner,
                       'lockCleanerStatements', lock_cleaner_statements,
                       'signalJanitor', signal_janitor,
                       'retention', retention) FROM sweeps),
    'harnessCalls', (SELECT coalesce(sum(calls), 0) FROM pg_stat_statements
                     WHERE userid <> 'maestro'::regrole),
    'top', (SELECT coalesce(json_agg(json_build_object(
                'calls', calls,
                'rows', rows,
                'toplevel', toplevel,
                'totalMs', round(total_exec_time::numeric, 3),
                'query', left(regexp_replace(query, '\s+', ' ', 'g'), 200))
              ORDER BY calls DESC, total_exec_time DESC), '[]'::json)
            FROM top))
FROM info;
