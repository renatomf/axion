-- The role of the webhooks module's jobs (events and webhooks design,
-- section 11). Its first job removes, across tenants, the signing secrets
-- whose grace period has ended (section 6.2). Its policies show it those
-- secrets only: even with a bug in its query, it can neither read nor
-- remove a secret that still signs. It reads no ciphertext. No role has
-- BYPASSRLS.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_webhooks_jobs') THEN
        CREATE ROLE axion_webhooks_jobs NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA webhooks TO axion_webhooks_jobs;
GRANT SELECT (id, expires_at), DELETE ON webhooks.secrets TO axion_webhooks_jobs;

-- A DELETE with a condition reads the rows it deletes, so it needs both.
CREATE POLICY ended_secrets_visible ON webhooks.secrets
    FOR SELECT TO axion_webhooks_jobs
    USING (expires_at <= now());

CREATE POLICY ended_secrets_removable ON webhooks.secrets
    FOR DELETE TO axion_webhooks_jobs
    USING (expires_at <= now());

-- The removal job finds the ended secrets by this index.
CREATE INDEX secrets_expires_at_idx ON webhooks.secrets (expires_at) WHERE expires_at IS NOT NULL;

-- The service switches to it with SET ROLE, as to a module role (V15).
GRANT axion_webhooks_jobs TO axion_app WITH INHERIT FALSE, SET TRUE;
