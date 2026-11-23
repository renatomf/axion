-- The role the webhooks module's code uses (events and webhooks design,
-- section 11). It registers endpoints and reads them. It stores an
-- endpoint's secret but cannot read one back: nothing reads a secret until
-- deliveries are signed.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_webhooks') THEN
        CREATE ROLE axion_webhooks NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA webhooks TO axion_webhooks;
GRANT SELECT, INSERT ON webhooks.endpoints TO axion_webhooks;
GRANT INSERT ON webhooks.secrets TO axion_webhooks;

-- The service switches to it with SET ROLE, as to the other module roles (V15).
GRANT axion_webhooks TO axion_app WITH INHERIT FALSE, SET TRUE;
