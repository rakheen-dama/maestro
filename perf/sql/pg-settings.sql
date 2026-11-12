-- The server's version and the settings a result depends on, each as
-- current_setting() renders it with its unit ("128MB", not "16384" + "8kB").
-- Runs as postgres.
SELECT json_build_object(
    'version', version(),
    'settings', (SELECT json_object_agg(name, current_setting(name) ORDER BY name)
                 FROM pg_settings
                 WHERE name IN ('max_connections', 'shared_buffers', 'fsync', 'synchronous_commit',
                                'wal_level', 'full_page_writes', 'work_mem')
                    OR name LIKE 'pg\_stat\_statements.%'));
