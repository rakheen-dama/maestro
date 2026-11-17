-- Instance rows NOT belonging to this run (workflow_id not starting with
-- :'prefix', the run's whole id space "<runId>-", warm-up included). Any is
-- leftover state; a cold run has none. Runs as postgres.
SELECT count(*)
FROM maestro_workflow_instance
WHERE workflow_id NOT LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%';
