-- Scenario 1's per-level table over this run's s1 rows (workflow_id starting
-- with :'prefix', "<runId>-s1-"; ids are "<prefix>L<level>-<i>", so the level
-- label is split_part(workflow_id, '-', 3)). For each level, its window
-- [ws, we] is that level's min/max(started_at): started_at is when the start
-- endpoint committed the row (an arrival), completed_at when the run became
-- terminal (COMPLETED, FAILED or TERMINATED). Per level:
--   arrivals, windowStart/windowEnd (and as epoch seconds), windowSecs,
--   arrivalPerSec = arrivals / windowSecs,
--   completionsInWindow = COMPLETED rows of ANY level with completed_at in
--     [ws, we], completionsInWindowPerSec = that / windowSecs,
--   backlogAtStart / backlogAtEnd = rows of any level started before ws
--     (at or before we) and not yet terminal at ws (at we),
--   p50Ms / p95Ms / p99Ms / maxMs = start-to-terminal of this level's
--     COMPLETED rows, completed / failed / nonTerminal of this level's rows.
-- unlabeled counts s1 rows whose label is not L<digits> (must be 0).
-- Runs as postgres.
WITH r AS (
    SELECT split_part(workflow_id, '-', 3) AS label, status, started_at, completed_at,
           extract(epoch FROM completed_at - started_at) * 1000 AS ms
    FROM maestro_workflow_instance
    WHERE workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'
), lv AS (
    SELECT substr(label, 2)::int AS level,
           count(*) AS arrivals,
           min(started_at) AS ws,
           max(started_at) AS we,
           count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
           count(*) FILTER (WHERE status = 'FAILED') AS failed,
           count(*) FILTER (WHERE status NOT IN ('COMPLETED', 'FAILED', 'TERMINATED')) AS non_terminal,
           percentile_cont(0.50) WITHIN GROUP (ORDER BY ms) FILTER (WHERE status = 'COMPLETED') AS p50,
           percentile_cont(0.95) WITHIN GROUP (ORDER BY ms) FILTER (WHERE status = 'COMPLETED') AS p95,
           percentile_cont(0.99) WITHIN GROUP (ORDER BY ms) FILTER (WHERE status = 'COMPLETED') AS p99,
           max(ms) FILTER (WHERE status = 'COMPLETED') AS pmax
    FROM r
    WHERE label ~ '^L[0-9]+$'
    GROUP BY label
), lw AS (
    SELECT lv.*, extract(epoch FROM we - ws) AS secs,
           (SELECT count(*) FROM r WHERE r.status = 'COMPLETED' AND r.completed_at BETWEEN lv.ws AND lv.we) AS done_in_window,
           (SELECT count(*) FROM r WHERE r.started_at < lv.ws
                AND (r.completed_at IS NULL OR r.completed_at >= lv.ws)) AS backlog_start,
           (SELECT count(*) FROM r WHERE r.started_at <= lv.we
                AND (r.completed_at IS NULL OR r.completed_at > lv.we)) AS backlog_end
    FROM lv
)
SELECT json_build_object(
    'levels', coalesce((SELECT json_agg(json_build_object(
        'level', level,
        'arrivals', arrivals,
        'windowStart', ws,
        'windowEnd', we,
        'windowStartEpoch', round(extract(epoch FROM ws)::numeric, 3),
        'windowEndEpoch', round(extract(epoch FROM we)::numeric, 3),
        'windowSecs', round(secs::numeric, 3),
        'arrivalPerSec', CASE WHEN secs > 0 THEN round((arrivals / secs)::numeric, 2) END,
        'completionsInWindow', done_in_window,
        'completionsInWindowPerSec', CASE WHEN secs > 0 THEN round((done_in_window / secs)::numeric, 2) END,
        'backlogAtStart', backlog_start,
        'backlogAtEnd', backlog_end,
        'p50Ms', round(p50::numeric, 1),
        'p95Ms', round(p95::numeric, 1),
        'p99Ms', round(p99::numeric, 1),
        'maxMs', round(pmax::numeric, 1),
        'completed', completed,
        'failed', failed,
        'nonTerminal', non_terminal) ORDER BY level) FROM lw), '[]'::json),
    'unlabeled', (SELECT count(*) FROM r WHERE label !~ '^L[0-9]+$'));
