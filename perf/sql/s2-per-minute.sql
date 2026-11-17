-- Scenario 2, phase C (full profile): the phase's recovered rows (workflow_id
-- starting with :'prefix') COMPLETED in each whole minute after :'t0' (the
-- database clock when node 1 was started), as [{minute, completed}], every
-- minute from 0 to the last one with a completion listed (0 when none).
-- Runs as postgres.
WITH c AS (
    SELECT floor(extract(epoch FROM completed_at - :'t0'::timestamptz) / 60)::int AS minute
    FROM maestro_workflow_instance
    WHERE status = 'COMPLETED'
      AND workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'
)
SELECT coalesce(json_agg(json_build_object('minute', g.minute, 'completed',
                         (SELECT count(*) FROM c WHERE c.minute = g.minute)) ORDER BY g.minute), '[]'::json)
FROM generate_series(0, (SELECT coalesce(max(minute), -1) FROM c)) AS g(minute);
