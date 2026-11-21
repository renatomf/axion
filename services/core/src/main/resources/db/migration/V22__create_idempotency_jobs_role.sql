-- The role of the job that removes expired idempotency keys across tenants
-- (idempotency design, section 6.2). It may only read and delete keys, and its
-- policies show it expired keys only: even with a bug in its query, it can
-- neither read a response still honored nor remove its key. No role has
-- BYPASSRLS.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'finaxis_idempotency_jobs') THEN
        CREATE ROLE finaxis_idempotency_jobs NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA idempotency TO finaxis_idempotency_jobs;
GRANT SELECT, DELETE ON idempotency.keys TO finaxis_idempotency_jobs;

-- A DELETE with a condition reads the rows it deletes, so it needs both.
CREATE POLICY expired_keys_visible ON idempotency.keys
    FOR SELECT TO finaxis_idempotency_jobs
    USING (expires_at <= now());

CREATE POLICY expired_keys_removable ON idempotency.keys
    FOR DELETE TO finaxis_idempotency_jobs
    USING (expires_at <= now());

-- The service switches to it with SET ROLE, as to a module role (V15).
GRANT finaxis_idempotency_jobs TO finaxis_app WITH INHERIT FALSE, SET TRUE;
