-- Timer lookup by logical ID, for WorkflowStore.findTimer.
--
-- Replay uses this to tell a timer that is genuinely still pending from one
-- that fired just before its owning node died: the row says FIRED but the
-- event log has no TIMER_FIRED. idx_wf_timer_due cannot serve that query —
-- it is partial on status = 'PENDING', which is exactly the rows replay is
-- not interested in.
--
-- Deliberately not UNIQUE. A timer ID is unique per instance in practice, but
-- saveTimer runs before the TIMER_SCHEDULED event append, so two nodes racing
-- the same live sleep both insert before the event log's unique index rejects
-- the loser. A unique index here would turn that benign race into a write
-- failure.
CREATE INDEX idx_wf_timer_lookup
    ON maestro_workflow_timer(workflow_instance_id, timer_id);
