-- {total, byStatus: {STATUS: n}} over this run's instance rows (workflow_id
-- starting with :'prefix'; LIKE metacharacters in the prefix are escaped).
-- Runs as postgres.
SELECT json_build_object(
    'total', coalesce(sum(n), 0),
    'byStatus', coalesce(json_object_agg(status, n), '{}'::json))
FROM (
    SELECT status, count(*) AS n
    FROM maestro_workflow_instance
    WHERE workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'
    GROUP BY status
) s;
