-- Cumulative progress of this run's s1 rows (workflow_id starting with
-- :'prefix') at one instant: Scenario 1's sampler runs it right after
-- pgss-window.sql, so each sample pairs the engine's cumulative statement
-- count with the cumulative completions it paid for. Runs as postgres.
SELECT json_build_object(
    'progressAt', now(),
    'rows', count(*),
    'completed', count(*) FILTER (WHERE status = 'COMPLETED'),
    'terminal', count(*) FILTER (WHERE status IN ('COMPLETED', 'FAILED', 'TERMINATED')))
FROM maestro_workflow_instance
WHERE workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%';
