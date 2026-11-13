-- Scenario 3: the engine's statements in the current pg_stat_statements
-- window, attributed to the engine component that issues them. Run in the
-- same psql session right after pgss-window.sql, so both describe the same
-- instant to within milliseconds. Top-level entries only, classified by the
-- statement's FULL text (whitespace collapsed; pg_stat_statements normalises
-- constants, 'PENDING' and false included, to $n) — never the 200-character
-- cut pgss-window.sql's list carries, which cannot tell the instance read by
-- workflow_id from the recovery page read (same 200-character head):
--   recheckInstance   SELECT ... FROM maestro_workflow_instance WHERE workflow_id = $1
--                     (WorkflowStore.getInstance: the parked run's stand-down read)
--   recheckSignals    ... FROM maestro_workflow_signal WHERE workflow_id = $1 AND
--                     signal_name = $2 AND consumed = ... (getUnconsumedSignals)
--   recheckTimerRow   ... FROM maestro_workflow_timer WHERE workflow_instance_id = $1
--                     AND timer_id = $2 (findTimer)
--   lockRenewAll      UPDATE maestro_distributed_lock l SET expires_at ... (renewAll)
--   lockRenewOne      UPDATE maestro_distributed_lock SET expires_at ... (renew)
--   lockAcquire       INSERT INTO maestro_distributed_lock ... (tryAcquire)
--   lockRelease       DELETE FROM maestro_distributed_lock WHERE lock_key = ...
--   recoveryPage      ... FROM maestro_workflow_instance WHERE service_name = $1 AND
--                     status IN (...) (getRecoverableInstances, one per page)
--   heldKeys          SELECT lock_key FROM maestro_distributed_lock WHERE lock_key = ANY
--   timerDuePoll      ... FROM maestro_workflow_timer WHERE fire_at <= $n AND status = ...
--   leaderElection    INSERT INTO maestro_leader_election ...
--   lockCleaner       DELETE FROM maestro_distributed_lock / maestro_leader_election
--                     WHERE expires_at < now() ...
-- Everything else is "other" (its ten largest entries are listed). The texts
-- are this engine's (traced from AbstractJdbcWorkflowStore and
-- PostgresDistributedLock); on an engine whose SQL differs a component reads
-- 0 and its calls land in "other", so the decomposition is advisory only.
-- nested = calls of statements nested in others (track=all). Runs as postgres.
WITH engine AS (
    SELECT toplevel, calls, total_exec_time, btrim(regexp_replace(query, '\s+', ' ', 'g')) AS q
    FROM pg_stat_statements
    WHERE userid = 'maestro'::regrole
), c AS (
    SELECT calls, total_exec_time, q,
        CASE
            WHEN q ~ '^SELECT .* FROM maestro_workflow_instance WHERE workflow_id = \$1$' THEN 'recheckInstance'
            WHEN q ~ 'FROM maestro_workflow_signal WHERE workflow_id = \$1 AND signal_name = \$2 AND consumed = ' THEN 'recheckSignals'
            WHEN q ~ 'FROM maestro_workflow_timer WHERE workflow_instance_id = \$1 AND timer_id = \$2' THEN 'recheckTimerRow'
            WHEN q ~ '^UPDATE maestro_distributed_lock l SET expires_at' THEN 'lockRenewAll'
            WHEN q ~ '^UPDATE maestro_distributed_lock SET expires_at' THEN 'lockRenewOne'
            WHEN q ~ '^INSERT INTO maestro_distributed_lock ' THEN 'lockAcquire'
            WHEN q ~ '^DELETE FROM maestro_distributed_lock WHERE lock_key = ' THEN 'lockRelease'
            WHEN q ~ 'FROM maestro_workflow_instance WHERE service_name = \$1 AND status IN ' THEN 'recoveryPage'
            WHEN q ~ '^SELECT lock_key FROM maestro_distributed_lock WHERE lock_key = ANY' THEN 'heldKeys'
            WHEN q ~ 'FROM maestro_workflow_timer WHERE fire_at <= \$[0-9]+ AND status = ' THEN 'timerDuePoll'
            WHEN q ~ '^INSERT INTO maestro_leader_election ' THEN 'leaderElection'
            WHEN q ~ '^DELETE FROM maestro_(distributed_lock|leader_election) WHERE expires_at < now\(\)' THEN 'lockCleaner'
            ELSE 'other'
        END AS component
    FROM engine
    WHERE toplevel
)
SELECT json_build_object(
    'componentsAt', now(),
    'components', (SELECT coalesce(json_object_agg(component, json_build_object(
                        'calls', calls, 'entries', entries, 'totalMs', round(ms::numeric, 3))), '{}'::json)
                   FROM (SELECT component, sum(calls) AS calls, count(*) AS entries, sum(total_exec_time) AS ms
                         FROM c GROUP BY component) s),
    'otherTop', (SELECT coalesce(json_agg(json_build_object('calls', calls, 'query', left(q, 200))
                                          ORDER BY calls DESC), '[]'::json)
                 FROM (SELECT calls, q FROM c WHERE component = 'other' ORDER BY calls DESC LIMIT 10) o),
    'nestedCalls', (SELECT coalesce(sum(calls), 0) FROM engine WHERE NOT toplevel));
