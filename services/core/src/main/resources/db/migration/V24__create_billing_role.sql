-- The role the billing module's code uses (billing design, section 11). It
-- may read, insert and update charges; no role may delete from billing, so a
-- charge, once created, stays to explain its payments and its entries.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'finaxis_billing') THEN
        CREATE ROLE finaxis_billing NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA billing TO finaxis_billing;
GRANT SELECT, INSERT, UPDATE ON billing.charges TO finaxis_billing;

-- The service switches to it with SET ROLE, as to the other module roles (V15).
GRANT finaxis_billing TO finaxis_app WITH INHERIT FALSE, SET TRUE;
