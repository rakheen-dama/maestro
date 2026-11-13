-- Indexes for the retention sweeper (WorkflowStore.purgeTerminalInstancesBefore).
-- Band 1-99 belongs to maestro-store-postgres (see V1). This migration names
-- only V1 objects, so it applies in any order relative to V6/V7.
--
-- Both statements use IF NOT EXISTS on purpose. Flyway runs this file in one
-- transaction, so CREATE INDEX CONCURRENTLY cannot be used here, and a plain
-- CREATE INDEX holds a SHARE lock on the table for the duration of the build —
-- writes block, reads do not. On a large table, pre-create both indexes by
-- hand with CREATE INDEX CONCURRENTLY under exactly these names before
-- deploying this build; this migration then finds them and does nothing.

-- Candidate select: status = ? AND completed_at < ? ORDER BY completed_at.
-- Partial on completed_at IS NOT NULL — every candidate has one, and the
-- planner can still use the index for generic (status, completed_at) plans.
CREATE INDEX IF NOT EXISTS idx_wf_instance_terminal
    ON maestro_workflow_instance (status, completed_at)
    WHERE completed_at IS NOT NULL;

-- Child delete by parent: DELETE FROM maestro_workflow_signal WHERE
-- workflow_instance_id IN (...). The existing signal indexes are keyed by
-- workflow_id, so without this one each batch would scan the signal table.
-- (Events and timers already have workflow_instance_id-leading indexes:
-- idx_wf_event_replay and idx_wf_timer_lookup.)
CREATE INDEX IF NOT EXISTS idx_wf_signal_instance
    ON maestro_workflow_signal (workflow_instance_id);
