-- The in-database activity sampler (WP12): what the engine's backends are
-- doing, ten times a second, from inside Postgres. The harness's own
-- `docker compose exec … psql` costs about 100 ms per call, too slow to see a
-- busy pool or a sweep's duty cycle; a procedure that loops in one session
-- does not. Runs as postgres, so neither this nor its inserts are ever
-- counted as engine load (pg_stat_statements userid 'maestro').
--
-- Load once per run (idempotent), then launch in the background:
--   dc exec -T postgres psql -U postgres -d maestro -c \
--     "CALL perf_harness.sample_activity(<secs>, '<label>', '<regex>')" &
-- Each iteration inserts one row: the engine's (role maestro) backends with
-- state 'active' whose current query matches <regex> (matching), and all of
-- its active backends (maestro_active); then COMMIT and pg_sleep(0.1). The
-- COMMIT ends the transaction, so the next read of pg_stat_activity takes a
-- fresh snapshot (within one transaction Postgres serves the same one);
-- pg_stat_clear_snapshot() makes that explicit. perf/sql/activity-samples.sql
-- summarises a label.
--
-- The sample table is UNLOGGED and the procedure commits with
-- synchronous_commit off: a sample's COMMIT then waits for no WAL flush, so
-- the sampler keeps its 10 Hz while the very work it watches (S6's purge)
-- floods the WAL. With a logged table the first S6 runs saw gaps of 0.4-0.6 s
-- under the sweep (review r8; validity.samplerResolution judges the gap).
CREATE SCHEMA IF NOT EXISTS perf_harness;

CREATE UNLOGGED TABLE IF NOT EXISTS perf_harness.activity_sample (
    at             timestamptz NOT NULL,
    label          text        NOT NULL,
    matching       int         NOT NULL,
    maestro_active int         NOT NULL
);

CREATE OR REPLACE PROCEDURE perf_harness.sample_activity(secs int, label text, pattern text)
LANGUAGE plpgsql AS $$
DECLARE
    deadline timestamptz := clock_timestamp() + make_interval(secs => secs);
BEGIN
    SET synchronous_commit = off;
    WHILE clock_timestamp() < deadline LOOP
        PERFORM pg_stat_clear_snapshot();
        INSERT INTO perf_harness.activity_sample (at, label, matching, maestro_active)
        SELECT clock_timestamp(), sample_activity.label,
               count(*) FILTER (WHERE a.query ~ sample_activity.pattern),
               count(*)
        FROM pg_stat_activity a
        WHERE a.usename = 'maestro' AND a.state = 'active';
        COMMIT;
        PERFORM pg_sleep(0.1);
    END LOOP;
END
$$;
