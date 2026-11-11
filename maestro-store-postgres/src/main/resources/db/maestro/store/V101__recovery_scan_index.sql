-- Index for the paged, service-scoped recovery scan
-- (WorkflowStore.getRecoverableInstances(serviceName, after, limit)).
-- Numbered V101, not V10: every new Maestro migration, store or lock, takes the
-- next version above the highest shipped, within 101-199 (V1-V9 and V100 are
-- history; 200-299 is retired). Every build since the lock band joined this
-- module resolves V100, so to an older build an applied V101 is a "future"
-- migration, which Flyway ignores by default, where an applied V10 would be a
-- "missing" one that fails its startup. This directory still says the
-- migration belongs to the store. It names only V1 objects.
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
