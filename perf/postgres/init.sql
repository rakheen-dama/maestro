-- Runs once, as the postgres superuser, when the (always fresh) volume is
-- initialised. The engine connects as `maestro`; the harness connects as
-- `postgres`, so pg_stat_statements can tell the engine's statements
-- (userid = 'maestro'::regrole) from the harness's own.
CREATE ROLE maestro LOGIN PASSWORD 'maestro';
CREATE DATABASE maestro OWNER maestro;
\connect maestro
CREATE EXTENSION pg_stat_statements;
