-- Starts a pg_stat_statements measurement window: clears every entry (and,
-- with all arguments defaulted, the dealloc counter and stats_reset), then
-- returns {"resetAt"}. Runs as postgres.
\o /dev/null
SELECT pg_stat_statements_reset();
\o
SELECT json_build_object('resetAt', stats_reset) FROM pg_stat_statements_info;
