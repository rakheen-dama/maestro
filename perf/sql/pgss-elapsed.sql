-- Seconds since the current pg_stat_statements window opened (its
-- stats_reset), on the database clock, the one every window length is
-- measured on. Runs as postgres.
SELECT round(extract(epoch FROM now() - stats_reset)::numeric, 3) FROM pg_stat_statements_info;
