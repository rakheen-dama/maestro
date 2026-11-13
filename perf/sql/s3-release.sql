-- Scenario 3's release check over the parked population (workflow_id
-- starting with :'park', "<runId>-park-"): how every row ended, its result,
-- and the release signal rows behind it. Reads the instance row's status,
-- completed_at and output (V1 columns) and the signal row's workflow_id,
-- signal_name and consumed (V1 columns): nothing an older engine lacks.
--   released     COMPLETED with output exactly the JSON string "released"
--   results      COMPLETED rows by their output text (released / timeout / …)
--   signals      perf.release (:'signal') rows of these workflows: total,
--                consumed, unconsumed, and how many workflows have not exactly
--                one row (a duplicate delivery) or not exactly one consumed
--                row (a lost or double consumption)
--   firstCompletedAt / lastCompletedAt  over the COMPLETED rows
-- Runs as postgres.
WITH p AS (
    SELECT workflow_id, status, completed_at, output #>> '{}' AS result
    FROM maestro_workflow_instance
    WHERE workflow_id LIKE replace(replace(replace(:'park', '\', '\\'), '%', '\%'), '_', '\_') || '%'
), s AS (
    SELECT s.workflow_id, count(*) AS n, count(*) FILTER (WHERE s.consumed) AS consumed
    FROM maestro_workflow_signal s JOIN p ON p.workflow_id = s.workflow_id
    WHERE s.signal_name = :'signal'
    GROUP BY s.workflow_id
)
SELECT json_build_object(
    'at', now(),
    'total', (SELECT count(*) FROM p),
    'byStatus', (SELECT coalesce(json_object_agg(status, n), '{}'::json)
                 FROM (SELECT status, count(*) AS n FROM p GROUP BY status) g),
    'released', (SELECT count(*) FROM p WHERE status = 'COMPLETED' AND result = 'released'),
    'results', (SELECT coalesce(json_object_agg(coalesce(result, 'null'), n), '{}'::json)
                FROM (SELECT result, count(*) AS n FROM p WHERE status = 'COMPLETED' GROUP BY result) g),
    'firstCompletedAt', (SELECT min(completed_at) FROM p WHERE status = 'COMPLETED'),
    'lastCompletedAt', (SELECT max(completed_at) FROM p WHERE status = 'COMPLETED'),
    'signals', json_build_object(
        'rows', (SELECT coalesce(sum(n), 0) FROM s),
        'consumed', (SELECT coalesce(sum(consumed), 0) FROM s),
        'unconsumed', (SELECT coalesce(sum(n - consumed), 0) FROM s),
        'workflowsWithSignal', (SELECT count(*) FROM s),
        'workflowsNotExactlyOneRow', (SELECT count(*) FROM p LEFT JOIN s USING (workflow_id) WHERE coalesce(s.n, 0) <> 1),
        'workflowsNotExactlyOneConsumed', (SELECT count(*) FROM p LEFT JOIN s USING (workflow_id) WHERE coalesce(s.consumed, 0) <> 1)));
