DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'konexio_app') THEN
        CREATE ROLE konexio_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'konexio_migrator') THEN
        CREATE ROLE konexio_migrator NOLOGIN;
    END IF;
END
$$;
