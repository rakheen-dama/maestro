-- Scenario 3: the parked population and everything else of this run at one
-- instant. park = rows whose workflow_id starts with :'park' ("<runId>-park-"),
-- others = the rest of the run's id space (:'run', "<runId>-": warm-up and
-- probe rows). open = rows not COMPLETED, FAILED or TERMINATED. LIKE
-- metacharacters in both prefixes are escaped. Runs as postgres.
WITH r AS (
    SELECT workflow_id LIKE replace(replace(replace(:'park', '\', '\\'), '%', '\%'), '_', '\_') || '%' AS is_park,
           status
    FROM maestro_workflow_instance
    WHERE workflow_id LIKE replace(replace(replace(:'run', '\', '\\'), '%', '\%'), '_', '\_') || '%'
), g AS (
    SELECT is_park, status, count(*) AS n FROM r GROUP BY is_park, status
)
SELECT json_build_object(
    'at', now(),
    'park', json_build_object(
        'total', (SELECT coalesce(sum(n), 0) FROM g WHERE is_park),
        'open', (SELECT coalesce(sum(n), 0) FROM g WHERE is_park AND status NOT IN ('COMPLETED', 'FAILED', 'TERMINATED')),
        'byStatus', (SELECT coalesce(json_object_agg(status, n), '{}'::json) FROM g WHERE is_park)),
    'others', json_build_object(
        'total', (SELECT coalesce(sum(n), 0) FROM g WHERE NOT is_park),
        'open', (SELECT coalesce(sum(n), 0) FROM g WHERE NOT is_park AND status NOT IN ('COMPLETED', 'FAILED', 'TERMINATED')),
        'byStatus', (SELECT coalesce(json_object_agg(status, n), '{}'::json) FROM g WHERE NOT is_park)));
