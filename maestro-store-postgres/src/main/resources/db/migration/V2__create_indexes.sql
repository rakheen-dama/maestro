-- Performance indexes for the Maestro workflow engine.

-- Event replay: unique index enforces (instance, sequence) uniqueness
-- and serves as the primary lookup for the memoization engine.
CREATE UNIQUE INDEX idx_wf_event_replay
    ON maestro_workflow_event(workflow_instance_id, sequence_number);

-- Recoverable instance lookup: partial index on active statuses only.
-- Used during startup recovery to find interrupted workflows.
CREATE INDEX idx_wf_instance_recoverable
    ON maestro_workflow_instance(status)
    WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING');

-- Due timer polling: partial index on PENDING timers ordered by fire_at.
-- Supports the leader-elected timer poller's SELECT ... FOR UPDATE SKIP LOCKED.
CREATE INDEX idx_wf_timer_due
    ON maestro_workflow_timer(fire_at, status)
    WHERE status = 'PENDING';

-- Unconsumed signal lookup by workflow ID and signal name.
-- Used by awaitSignal() to check for pre-delivered signals.
CREATE INDEX idx_wf_signal_pending
    ON maestro_workflow_signal(workflow_id, signal_name, consumed)
    WHERE consumed = false;

-- Orphaned signal adoption: finds signals persisted before the workflow
-- instance existed (pre-delivery pattern).
CREATE INDEX idx_wf_signal_orphan
    ON maestro_workflow_signal(workflow_id, consumed)
    WHERE workflow_instance_id IS NULL AND consumed = false;
