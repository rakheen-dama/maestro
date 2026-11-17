-- Scenario 2 (s2-recovery-drain): captures the template run :'wf' as a
-- consistent mid-run snapshot, or nothing. In ONE repeatable-read
-- transaction (one snapshot for every statement), and only while the run is
-- RUNNING with exactly one ACTIVITY_COMPLETED event (step 0 done, step 1 not
-- yet), it copies the instance row and all its events into
-- perf_harness.s2_tmpl_instance / s2_tmpl_event (emptied first, so a
-- failed capture leaves them empty). Every column of both tables is copied
-- (CREATE TABLE ... (LIKE ...)), so the clones carry whatever this engine's
-- schema has (lease_epoch, args_hash, ...). Returns {captured, status,
-- events, activityCompleted, eventTypes, sequences}. The harness polls it
-- every 200 ms until captured. Runs as postgres.
\o /dev/null
SET client_min_messages = warning;
CREATE SCHEMA IF NOT EXISTS perf_harness;
CREATE TABLE IF NOT EXISTS perf_harness.s2_tmpl_instance (LIKE maestro_workflow_instance);
CREATE TABLE IF NOT EXISTS perf_harness.s2_tmpl_event (LIKE maestro_workflow_event);
BEGIN ISOLATION LEVEL REPEATABLE READ;
DELETE FROM perf_harness.s2_tmpl_event;
DELETE FROM perf_harness.s2_tmpl_instance;
INSERT INTO perf_harness.s2_tmpl_instance
SELECT i.* FROM maestro_workflow_instance i
WHERE i.workflow_id = :'wf' AND i.status = 'RUNNING'
  AND (SELECT count(*) FROM maestro_workflow_event e
       WHERE e.workflow_instance_id = i.id AND e.event_type = 'ACTIVITY_COMPLETED') = 1;
INSERT INTO perf_harness.s2_tmpl_event
SELECT e.* FROM maestro_workflow_event e
WHERE e.workflow_instance_id IN (SELECT id FROM perf_harness.s2_tmpl_instance);
COMMIT;
\o
SELECT json_build_object(
    'captured', (SELECT count(*) FROM perf_harness.s2_tmpl_instance) = 1,
    'status', (SELECT status FROM perf_harness.s2_tmpl_instance),
    'events', (SELECT count(*) FROM perf_harness.s2_tmpl_event),
    'activityCompleted', (SELECT count(*) FROM perf_harness.s2_tmpl_event WHERE event_type = 'ACTIVITY_COMPLETED'),
    'eventTypes', (SELECT coalesce(json_agg(event_type ORDER BY sequence_number), '[]'::json) FROM perf_harness.s2_tmpl_event),
    'sequences', (SELECT coalesce(json_agg(sequence_number ORDER BY sequence_number), '[]'::json) FROM perf_harness.s2_tmpl_event),
    'liveStatus', (SELECT status FROM maestro_workflow_instance WHERE workflow_id = :'wf'));
