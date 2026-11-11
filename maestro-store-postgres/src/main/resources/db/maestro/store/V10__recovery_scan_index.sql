-- Index for the paged, service-scoped recovery scan
-- (WorkflowStore.getRecoverableInstances(serviceName, after, limit)).
-- Band 1-99 belongs to maestro-store-postgres (see V1). This migration names
-- only V1 objects, so it applies in any order relative to V6-V9.
--
-- The statement uses IF NOT EXISTS on purpose. Flyway runs this file in one
-- transaction, so CREATE INDEX CONCURRENTLY cannot be used here, and a plain
-- CREATE INDEX holds a SHARE lock on the table for the duration of the build —
-- writes block, reads do not. On a large table, pre-create the index by hand
-- with CREATE INDEX CONCURRENTLY under exactly this name before deploying this
-- build; this migration then finds it and does nothing.
--
-- The index is an optimisation only: the scan's query is correct without it,
-- so a node on this build runs against a database that does not have it yet
-- (maestro.store.schema.mode: none, or a rolling deploy) — only slower.

-- Recovery page: service_name = ? AND status IN (active) AND (started_at, id) > (?, ?)
-- ORDER BY started_at, id LIMIT ?. Partial on the active statuses — the scan
-- reads only those, and terminal rows (the bulk of the table) stay out of it.
CREATE INDEX IF NOT EXISTS idx_wf_instance_recovery
    ON maestro_workflow_instance (service_name, started_at, id)
    WHERE status IN ('RUNNING', 'WAITING_SIGNAL', 'WAITING_TIMER', 'COMPENSATING');
