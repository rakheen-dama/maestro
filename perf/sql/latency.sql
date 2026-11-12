-- End-to-end latency (completed_at - started_at) of this run's COMPLETED rows
-- (workflow_id starting with :'prefix'). Runs as postgres.
SELECT json_build_object(
    'completed', count(*),
    'p50Ms', round(percentile_cont(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 1),
    'p95Ms', round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 1),
    'p99Ms', round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 1),
    'maxMs', round(max(ms)::numeric, 1))
FROM (
    SELECT extract(epoch FROM completed_at - started_at) * 1000 AS ms
    FROM maestro_workflow_instance
    WHERE status = 'COMPLETED'
      AND workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'
) t;
