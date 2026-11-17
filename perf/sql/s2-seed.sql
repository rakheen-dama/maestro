-- Scenario 2 (s2-recovery-drain): seeds :n clones of the template snapshot
-- (s2-template.sql) as workflow ids :'prefix'<i>, i = 0 .. :n - 1: each an
-- orphaned RUNNING run whose node died after step 0 completed and before
-- step 1's outcome. Every column of the template row and of each of its
-- events is copied (jsonb_populate_record over the row's JSON, its SQL NULL
-- columns left out so they stay NULL rather than becoming JSON null); only
-- these are overridden:
--   instance  id, run_id (new uuids), workflow_id, status RUNNING,
--             started_at / updated_at = template started_at + i ms (keyset
--             order), completed_at / output NULL
--   event     id (new uuid), workflow_instance_id (the clone's id)
-- No lock, signal or timer row is written. Refuses (RAISE EXCEPTION) when the
-- snapshot is missing or any engine node is connected (a role-maestro
-- backend): seeding under a live node would race its recovery poller. The
-- harness also checks that no node container is running. Then ANALYZE, as a
-- real backlog would have statistics. Returns
-- {seeded, events, at}. Runs as postgres.
\o /dev/null
DO $$
BEGIN
    IF to_regclass('perf_harness.s2_tmpl_instance') IS NULL
       OR to_regclass('perf_harness.s2_tmpl_event') IS NULL
       OR (SELECT count(*) FROM perf_harness.s2_tmpl_instance) <> 1 THEN
        RAISE EXCEPTION 's2-seed: no template snapshot (run s2-template.sql first)';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_stat_activity WHERE usename = 'maestro') THEN
        RAISE EXCEPTION 's2-seed: an engine node is connected; stop every node before seeding';
    END IF;
END
$$;
BEGIN;
CREATE TEMP TABLE s2_map ON COMMIT DROP AS
SELECT g AS i, gen_random_uuid() AS id FROM generate_series(0, :n - 1) g;
INSERT INTO maestro_workflow_instance
SELECT r.*
FROM perf_harness.s2_tmpl_instance t
CROSS JOIN s2_map m
CROSS JOIN LATERAL jsonb_populate_record(NULL::maestro_workflow_instance,
    (SELECT jsonb_object_agg(key, value) FROM jsonb_each(to_jsonb(t))
     WHERE value <> 'null'::jsonb AND key NOT IN ('completed_at', 'output'))
    || jsonb_build_object(
        'id', m.id,
        'run_id', gen_random_uuid(),
        'workflow_id', :'prefix' || m.i,
        'status', 'RUNNING',
        'started_at', t.started_at + make_interval(secs => m.i / 1000.0),
        'updated_at', t.started_at + make_interval(secs => m.i / 1000.0))) r;
INSERT INTO maestro_workflow_event
SELECT r.*
FROM perf_harness.s2_tmpl_event e
CROSS JOIN s2_map m
CROSS JOIN LATERAL jsonb_populate_record(NULL::maestro_workflow_event,
    (SELECT jsonb_object_agg(key, value) FROM jsonb_each(to_jsonb(e)) WHERE value <> 'null'::jsonb)
    || jsonb_build_object('id', gen_random_uuid(), 'workflow_instance_id', m.id)) r;
COMMIT;
ANALYZE maestro_workflow_instance;
ANALYZE maestro_workflow_event;
\o
SELECT json_build_object(
    'seeded', (SELECT count(*) FROM maestro_workflow_instance
               WHERE workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'),
    'events', (SELECT count(*) FROM maestro_workflow_event e JOIN maestro_workflow_instance i ON i.id = e.workflow_instance_id
               WHERE i.workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'),
    'at', now());
