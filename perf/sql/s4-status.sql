-- Scenario 4 (s4-signals): the state of one phase's perf-signals runs
-- (workflow_id starting with :'prefix') and their signal rows.
--
-- The signal a SIGNAL_RECEIVED event consumed is named by its step_name:
-- SignalManager.consumeRow (maestro-core SignalManager.java:1368-1376) writes
-- the memo under stepName = STEP_PREFIX + signalName, STEP_PREFIX =
-- "$maestro:awaitSignal:" (SignalManager.java:106; the same at 0c710a1e).
-- So each consumed signal row (workflow_id, signal_name) pairs with exactly
-- one SIGNAL_RECEIVED event of its run under step_name
-- '$maestro:awaitSignal:' || signal_name, and the consume latency is that
-- event's created_at - the row's received_at (both written by the engine;
-- milliseconds).
--
-- Returns {total, byStatus, outputs (COMPLETED rows by output text),
--  signals: {rows, consumed, unconsumed, keysNotExactlyOneRow (expected
--  (workflow, name) keys, :'m' per run, without exactly one row)},
--  latencyMs: {paired, p50, p95, max}}. Runs as postgres.
WITH p AS (
    SELECT id, workflow_id, status, output #>> '{}' AS result
    FROM maestro_workflow_instance
    WHERE workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'
), s AS (
    SELECT s.workflow_id, s.signal_name, s.consumed, s.received_at
    FROM maestro_workflow_signal s JOIN p ON p.workflow_id = s.workflow_id
), keys AS (
    SELECT p.workflow_id, 'perf.s' || k AS signal_name
    FROM p CROSS JOIN generate_series(0, :m - 1) k
), per_key AS (
    SELECT keys.workflow_id, keys.signal_name, count(s.workflow_id) AS n
    FROM keys LEFT JOIN s USING (workflow_id, signal_name)
    GROUP BY keys.workflow_id, keys.signal_name
), lat AS (
    SELECT extract(epoch FROM e.created_at - s.received_at) * 1000 AS ms
    FROM s
    JOIN p ON p.workflow_id = s.workflow_id
    JOIN maestro_workflow_event e
      ON e.workflow_instance_id = p.id AND e.event_type = 'SIGNAL_RECEIVED'
     AND e.step_name = '$maestro:awaitSignal:' || s.signal_name
    WHERE s.consumed
)
SELECT json_build_object(
    'total', (SELECT count(*) FROM p),
    'byStatus', (SELECT coalesce(json_object_agg(status, n), '{}'::json)
                 FROM (SELECT status, count(*) AS n FROM p GROUP BY status) g),
    'outputs', (SELECT coalesce(json_object_agg(coalesce(result, 'null'), n), '{}'::json)
                FROM (SELECT result, count(*) AS n FROM p WHERE status = 'COMPLETED' GROUP BY result) g),
    'signals', json_build_object(
        'rows', (SELECT count(*) FROM s),
        'consumed', (SELECT count(*) FROM s WHERE consumed),
        'unconsumed', (SELECT count(*) FROM s WHERE NOT consumed),
        'keysNotExactlyOneRow', (SELECT count(*) FROM per_key WHERE n <> 1)),
    'latencyMs', json_build_object(
        'paired', (SELECT count(*) FROM lat),
        'p50', (SELECT round(percentile_cont(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 1) FROM lat),
        'p95', (SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 1) FROM lat),
        'max', (SELECT round(max(ms)::numeric, 1) FROM lat)));
