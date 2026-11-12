-- The server's version and the settings a result depends on. Runs as postgres.
SELECT json_build_object(
    'version', version(),
    'settings', (SELECT json_object_agg(name, setting || coalesce(unit, '') ORDER BY name)
                 FROM pg_settings
                 WHERE name IN ('max_connections', 'shared_buffers')
                    OR name LIKE 'pg\_stat\_statements.%'));
