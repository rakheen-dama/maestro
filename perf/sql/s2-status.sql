-- Scenario 2 (s2-recovery-drain): the state of one phase's rows (workflow_id
-- starting with :'prefix'): {total, byStatus, outputs (COMPLETED rows by
-- output text), events (their event rows), lockRows (instance-lock rows
-- naming one of them: key "...workflow:<workflowId>"), firstStartedAt,
-- lastCompletedAt, dbNow}. Runs as postgres.
WITH p AS (
    SELECT id, workflow_id, status, output #>> '{}' AS result, started_at, completed_at
    FROM maestro_workflow_instance
    WHERE workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'
)
SELECT json_build_object(
    'total', (SELECT count(*) FROM p),
    'byStatus', (SELECT coalesce(json_object_agg(status, n), '{}'::json)
                 FROM (SELECT status, count(*) AS n FROM p GROUP BY status) g),
    'outputs', (SELECT coalesce(json_object_agg(coalesce(result, 'null'), n), '{}'::json)
                FROM (SELECT result, count(*) AS n FROM p WHERE status = 'COMPLETED' GROUP BY result) g),
    'events', (SELECT count(*) FROM maestro_workflow_event e JOIN p ON p.id = e.workflow_instance_id),
    'lockRows', (SELECT count(*) FROM maestro_distributed_lock l
                 WHERE l.lock_key LIKE '%workflow:' || replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'),
    'firstStartedAt', (SELECT min(started_at) FROM p),
    'lastCompletedAt', (SELECT max(completed_at) FROM p WHERE status = 'COMPLETED'),
    'dbNow', now());
