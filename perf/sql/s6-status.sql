-- Scenario 6 (s6-retention): per seed set (workflow_id :'prefix' + "<set>-"),
-- {instances, events, timers, signalsConsumed, signalsUnconsumed}, and the
-- orphan children of the WHOLE database (event, timer and signal rows whose
-- workflow_instance_id names no instance; pre-delivery signals, with no
-- instance id, are not orphans). Runs as postgres.
WITH i AS (
    SELECT id, substring(workflow_id FROM length(:'prefix') + 1) AS rest
    FROM maestro_workflow_instance
    WHERE workflow_id LIKE replace(replace(replace(:'prefix', '\', '\\'), '%', '\%'), '_', '\_') || '%'
), s AS (
    SELECT id, regexp_replace(rest, '-[0-9]+$', '') AS set FROM i
), n AS (SELECT set, count(*) AS c FROM s GROUP BY set
), ev AS (SELECT s.set, count(*) AS c FROM maestro_workflow_event e JOIN s ON s.id = e.workflow_instance_id GROUP BY s.set
), tm AS (SELECT s.set, count(*) AS c FROM maestro_workflow_timer t JOIN s ON s.id = t.workflow_instance_id GROUP BY s.set
), sg AS (SELECT s.set, count(*) FILTER (WHERE g.consumed) AS consumed, count(*) FILTER (WHERE NOT g.consumed) AS unconsumed
          FROM maestro_workflow_signal g JOIN s ON s.id = g.workflow_instance_id GROUP BY s.set
), per AS (
    SELECT n.set, n.c AS instances, coalesce(ev.c, 0) AS events, coalesce(tm.c, 0) AS timers,
           coalesce(sg.consumed, 0) AS consumed, coalesce(sg.unconsumed, 0) AS unconsumed
    FROM n LEFT JOIN ev USING (set) LEFT JOIN tm USING (set) LEFT JOIN sg USING (set)
)
SELECT json_build_object(
    'sets', (SELECT coalesce(json_object_agg(set, json_build_object('instances', instances, 'events', events,
                 'timers', timers, 'signalsConsumed', consumed, 'signalsUnconsumed', unconsumed)), '{}'::json) FROM per),
    'orphans', json_build_object(
        'events', (SELECT count(*) FROM maestro_workflow_event e
                   WHERE NOT EXISTS (SELECT 1 FROM maestro_workflow_instance i WHERE i.id = e.workflow_instance_id)),
        'timers', (SELECT count(*) FROM maestro_workflow_timer t
                   WHERE NOT EXISTS (SELECT 1 FROM maestro_workflow_instance i WHERE i.id = t.workflow_instance_id)),
        'signals', (SELECT count(*) FROM maestro_workflow_signal g
                    WHERE g.workflow_instance_id IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM maestro_workflow_instance i WHERE i.id = g.workflow_instance_id))),
    'at', now());
