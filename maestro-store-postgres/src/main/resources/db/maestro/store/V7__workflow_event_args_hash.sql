-- Argument fingerprint: the SHA-256 (64 lowercase hex characters) of the
-- arguments an activity was invoked with, written on ACTIVITY_COMPLETED and
-- ACTIVITY_FAILED events by io.b2mash.maestro.core.engine.ArgumentFingerprint.
--
-- Memoization matches a stored outcome to a replayed step by sequence number
-- and step name. Neither sees the arguments: a workflow whose activity input
-- depends on something non-deterministic (a HashMap's iteration order, the
-- clock, a per-node config value) replayed the recorded result for an input
-- the recorded run never saw, silently. At replay the engine recomputes the
-- fingerprint of the current call and stands the run down as
-- nondeterministic_history when it differs from the stored one
-- (ReplayGuard.requireArgumentsMatch; maestro.replay.verify-arguments).
--
-- Nullable, no default, no index: NULL means "unverified" — every row an
-- earlier build wrote, every event type other than an activity outcome, a
-- row an older node writes mid-rollout, and arguments the mapper could not
-- serialize — and the replay guard accepts it. So this is rolling-deploy
-- safe in both directions: an older node's inserts omit the column and read
-- back as NULL; a newer node's rows carry a hash an older node never reads.
-- ADD COLUMN of a nullable column without a default is metadata-only on
-- PostgreSQL (no table rewrite), so this is safe to apply to a live table.
--
-- Band 1-99 belongs to maestro-store-postgres (see V1). Names only V1 objects,
-- so it applies in any order relative to V6 and V8+.
ALTER TABLE maestro_workflow_event
    ADD COLUMN args_hash VARCHAR(64);

COMMENT ON COLUMN maestro_workflow_event.args_hash IS
    'SHA-256 hex of the activity arguments (ACTIVITY_COMPLETED/ACTIVITY_FAILED only); NULL = unverified';
