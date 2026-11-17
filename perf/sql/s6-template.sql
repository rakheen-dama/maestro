-- Scenario 6 (s6-retention): copies the released park run :'wf' (COMPLETED,
-- with its events, its cancelled await-timeout timer row and its consumed
-- signal row) into perf_harness.s6_tmpl_instance / _event / _timer /
-- _signal (every column: CREATE TABLE ... (LIKE ...)), and creates
-- perf_harness.s6_clone, which s6-seed.sql calls once per seed set. Returns
-- {captured, status, events, timers, signals, signalsConsumed, eventTypes}.
-- Runs as postgres.
\o /dev/null
SET client_min_messages = warning;
CREATE SCHEMA IF NOT EXISTS perf_harness;
DROP TABLE IF EXISTS perf_harness.s6_tmpl_instance, perf_harness.s6_tmpl_event,
                     perf_harness.s6_tmpl_timer, perf_harness.s6_tmpl_signal;
CREATE TABLE perf_harness.s6_tmpl_instance (LIKE maestro_workflow_instance);
CREATE TABLE perf_harness.s6_tmpl_event (LIKE maestro_workflow_event);
CREATE TABLE perf_harness.s6_tmpl_timer (LIKE maestro_workflow_timer);
CREATE TABLE perf_harness.s6_tmpl_signal (LIKE maestro_workflow_signal);
BEGIN ISOLATION LEVEL REPEATABLE READ;
INSERT INTO perf_harness.s6_tmpl_instance
SELECT * FROM maestro_workflow_instance WHERE workflow_id = :'wf' AND status = 'COMPLETED';
INSERT INTO perf_harness.s6_tmpl_event
SELECT * FROM maestro_workflow_event WHERE workflow_instance_id IN (SELECT id FROM perf_harness.s6_tmpl_instance);
INSERT INTO perf_harness.s6_tmpl_timer
SELECT * FROM maestro_workflow_timer WHERE workflow_instance_id IN (SELECT id FROM perf_harness.s6_tmpl_instance);
INSERT INTO perf_harness.s6_tmpl_signal
SELECT * FROM maestro_workflow_signal WHERE workflow_instance_id IN (SELECT id FROM perf_harness.s6_tmpl_instance);
COMMIT;

-- One seed set: :n clones of the template as workflow ids <prefix><i>, each
-- with status <status>, completed_at = now() - <age> - i ms (a strict
-- completed_at order inside the set), started_at = completed_at - 1 s,
-- updated_at = completed_at, service_name <service>; every child row of the
-- template cloned under the clone's id (new ids), the template's signal
-- consumed as it was; with <unconsumed>, one more signal row per clone
-- (perf.unconsumed, consumed = false). Columns not overridden are copied
-- from the template row (SQL NULLs left NULL, never JSON null).
CREATE OR REPLACE FUNCTION perf_harness.s6_clone(prefix text, n int, st text, age interval,
                                                 service text, unconsumed boolean)
RETURNS int LANGUAGE plpgsql AS $$
BEGIN
    CREATE TEMP TABLE s6_map ON COMMIT DROP AS
    SELECT g AS i, gen_random_uuid() AS id,
           now() - age - make_interval(secs => g / 1000.0) AS done
    FROM generate_series(0, n - 1) g;
    INSERT INTO maestro_workflow_instance
    SELECT r.* FROM perf_harness.s6_tmpl_instance t CROSS JOIN s6_map m
    CROSS JOIN LATERAL jsonb_populate_record(NULL::maestro_workflow_instance,
        (SELECT jsonb_object_agg(key, value) FROM jsonb_each(to_jsonb(t)) WHERE value <> 'null'::jsonb)
        || jsonb_build_object('id', m.id, 'run_id', gen_random_uuid(), 'workflow_id', prefix || m.i,
                              'status', st, 'service_name', service, 'completed_at', m.done,
                              'updated_at', m.done, 'started_at', m.done - interval '1 second')) r;
    INSERT INTO maestro_workflow_event
    SELECT r.* FROM perf_harness.s6_tmpl_event e CROSS JOIN s6_map m
    CROSS JOIN LATERAL jsonb_populate_record(NULL::maestro_workflow_event,
        (SELECT jsonb_object_agg(key, value) FROM jsonb_each(to_jsonb(e)) WHERE value <> 'null'::jsonb)
        || jsonb_build_object('id', gen_random_uuid(), 'workflow_instance_id', m.id)) r;
    INSERT INTO maestro_workflow_timer
    SELECT r.* FROM perf_harness.s6_tmpl_timer x CROSS JOIN s6_map m
    CROSS JOIN LATERAL jsonb_populate_record(NULL::maestro_workflow_timer,
        (SELECT jsonb_object_agg(key, value) FROM jsonb_each(to_jsonb(x)) WHERE value <> 'null'::jsonb)
        || jsonb_build_object('id', gen_random_uuid(), 'workflow_instance_id', m.id, 'workflow_id', prefix || m.i)) r;
    INSERT INTO maestro_workflow_signal
    SELECT r.* FROM perf_harness.s6_tmpl_signal x CROSS JOIN s6_map m
    CROSS JOIN LATERAL jsonb_populate_record(NULL::maestro_workflow_signal,
        (SELECT jsonb_object_agg(key, value) FROM jsonb_each(to_jsonb(x)) WHERE value <> 'null'::jsonb)
        || jsonb_build_object('id', gen_random_uuid(), 'workflow_instance_id', m.id, 'workflow_id', prefix || m.i)) r;
    IF unconsumed THEN
        INSERT INTO maestro_workflow_signal
        SELECT r.* FROM perf_harness.s6_tmpl_signal x CROSS JOIN s6_map m
        CROSS JOIN LATERAL jsonb_populate_record(NULL::maestro_workflow_signal,
            (SELECT jsonb_object_agg(key, value) FROM jsonb_each(to_jsonb(x))
             WHERE value <> 'null'::jsonb AND key <> 'message_id')
            || jsonb_build_object('id', gen_random_uuid(), 'workflow_instance_id', m.id, 'workflow_id', prefix || m.i,
                                  'signal_name', 'perf.unconsumed', 'consumed', false)) r;
    END IF;
    DROP TABLE s6_map;
    RETURN n;
END
$$;
\o
SELECT json_build_object(
    'captured', (SELECT count(*) FROM perf_harness.s6_tmpl_instance) = 1,
    'status', (SELECT status FROM perf_harness.s6_tmpl_instance),
    'events', (SELECT count(*) FROM perf_harness.s6_tmpl_event),
    'eventTypes', (SELECT coalesce(json_agg(event_type ORDER BY sequence_number), '[]'::json) FROM perf_harness.s6_tmpl_event),
    'timers', (SELECT count(*) FROM perf_harness.s6_tmpl_timer),
    'timerStatuses', (SELECT coalesce(json_agg(status), '[]'::json) FROM perf_harness.s6_tmpl_timer),
    'signals', (SELECT count(*) FROM perf_harness.s6_tmpl_signal),
    'signalsConsumed', (SELECT count(*) FROM perf_harness.s6_tmpl_signal WHERE consumed));
