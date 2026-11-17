-- Scenario 6 (s6-retention): start-to-complete latency of the foreground
-- probes (workflow_id starting with :'prefix'), bucketed by started_at into
-- pre (< :'a'), during ([:'a', :'b']) and post (> :'b'), where [a, b] is the
-- sampler's first to last sample with purge work active. Returns {pre,
-- during, post}: each {n, completed, p50Ms, p95Ms}. Runs as postgres.
WITH p AS (
    SELECT status, started_at, extract(epoch FROM completed_at - started_at) * 1000 AS ms,
           CASE WHEN started_at < :'a'::timestamptz THEN 'pre'
                WHEN started_at <= :'b'::timestamptz THEN 'during'
                ELSE 'post' END AS w
    FROM maestro_workflow_instance
    WHERE workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'
)
SELECT json_object_agg(w, json_build_object('n', n, 'completed', c, 'p50Ms', p50, 'p95Ms', p95))
FROM (
    SELECT w.w, count(p.status) AS n, count(*) FILTER (WHERE p.status = 'COMPLETED') AS c,
           round(percentile_cont(0.50) WITHIN GROUP (ORDER BY p.ms) FILTER (WHERE p.status = 'COMPLETED')::numeric, 1) AS p50,
           round(percentile_cont(0.95) WITHIN GROUP (ORDER BY p.ms) FILTER (WHERE p.status = 'COMPLETED')::numeric, 1) AS p95
    FROM (VALUES ('pre'), ('during'), ('post')) w(w) LEFT JOIN p ON p.w = w.w
    GROUP BY w.w
) g;
