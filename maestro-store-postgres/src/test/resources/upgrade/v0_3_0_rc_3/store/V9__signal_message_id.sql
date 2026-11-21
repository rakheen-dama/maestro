-- Signal identity: the producer-assigned id of the logical signal a row records.
--
-- Transports are at-least-once. Before this column every delivery of a signal
-- became a new row with a random primary key, so a Kafka record redelivered
-- after a crash or rebalance (between the committed insert and the offset
-- commit), a producer that republished, or an HTTP client that retried
-- WorkflowHandle.signal all left a twin row. A twin is harmless to a single
-- await but corrupts a workflow that awaits the same signal name more than
-- once (a review loop, collectSignals(name, n)): the twin answers the NEXT
-- await as if it were a new signal.
--
-- The engine now carries an id with each delivery — the SignalMessage envelope
-- field, else the maestro-message-id Kafka header, else the record's Kafka
-- coordinate (topic id, partition, offset) — and inserts through
-- WorkflowStore.saveSignalIfAbsent. The partial unique index below keeps at
-- most one row per (workflow_id, signal_name, message_id): a second delivery of
-- the same identity is a duplicate and writes nothing. Consumed and adopted
-- rows still count, so a redelivery after the signal was consumed is
-- suppressed too.
--
-- Nullable, no default: NULL means "no identity" (a producer that named
-- nothing, a row an earlier build wrote) and is never deduplicated — the index
-- is partial on message_id IS NOT NULL, so NULL rows never collide. ADD COLUMN
-- of a nullable column without a default is metadata-only on PostgreSQL (no
-- table rewrite).
--
-- This migration is NOT optional, including under
-- maestro.store.schema.mode=none: AbstractJdbcWorkflowStore names message_id
-- unconditionally in the signal INSERT and SELECT, so a signal table without
-- the column makes every delivery throw inside the transport listener.
--
-- The index uses IF NOT EXISTS on purpose. Flyway runs this file in one
-- transaction, so CREATE INDEX CONCURRENTLY cannot be used here; a plain
-- CREATE INDEX holds a SHARE lock on the signal table for the build (writes
-- block, reads do not). Every existing row has message_id NULL, so the partial
-- index is empty and builds in one scan; on a very large table, pre-create it
-- by hand with CREATE UNIQUE INDEX CONCURRENTLY under exactly this name before
-- deploying, and this migration then finds it and does nothing.
--
-- Band 1-99 belongs to maestro-store-postgres (see V1). Names only V1 objects,
-- so it applies in any order relative to V6-V8.
ALTER TABLE maestro_workflow_signal
    ADD COLUMN message_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_wf_signal_message_id
    ON maestro_workflow_signal (workflow_id, signal_name, message_id)
    WHERE message_id IS NOT NULL;

COMMENT ON COLUMN maestro_workflow_signal.message_id IS
    'Producer-assigned identity of the logical signal; unique per (workflow_id, signal_name) when not NULL; NULL = no identity';
