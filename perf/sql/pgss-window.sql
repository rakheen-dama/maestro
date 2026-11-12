-- Closes a pg_stat_statements measurement window opened by pgss-reset.sql.
-- maestroCalls counts only the engine's role; everything else (this harness
-- runs as postgres) is harnessCalls and never measured. dealloc > 0 means
-- entries were evicted and the counts are incomplete. Runs as postgres.
WITH info AS (
    SELECT stats_reset, dealloc FROM pg_stat_statements_info
), engine AS (
    SELECT calls, rows, total_exec_time, query
    FROM pg_stat_statements
    WHERE userid = 'maestro'::regrole
), top AS (
    SELECT calls, rows, total_exec_time, query
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
    'harnessCalls', (SELECT coalesce(sum(calls), 0) FROM pg_stat_statements
                     WHERE userid <> 'maestro'::regrole),
    'top', (SELECT coalesce(json_agg(json_build_object(
                'calls', calls,
                'rows', rows,
                'totalMs', round(total_exec_time::numeric, 3),
                'query', left(regexp_replace(query, '\s+', ' ', 'g'), 200))
              ORDER BY calls DESC, total_exec_time DESC), '[]'::json)
            FROM top))
FROM info;
