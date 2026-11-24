-- The role the audit module's code uses to append to a chain (events and
-- webhooks design, sections 9.2 and 11). It inserts records and reads them,
-- never updates or deletes one; it inserts and moves its chain's head.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_audit') THEN
        CREATE ROLE axion_audit NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA audit TO axion_audit;
GRANT SELECT, INSERT ON audit.records TO axion_audit;
GRANT SELECT, INSERT, UPDATE ON audit.chain_heads TO axion_audit;

-- Row-Level Security (tenancy design, section 5.4): the role reaches only
-- the chain of the tenant bound to its transaction, and with none bound,
-- none at all.
CREATE POLICY tenant_isolation ON audit.records
    TO axion_audit
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

CREATE POLICY tenant_isolation ON audit.chain_heads
    TO axion_audit
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

-- A policy runs with the privileges of the role querying the table.
GRANT USAGE ON SCHEMA shared TO axion_audit;
GRANT EXECUTE ON FUNCTION shared.current_tenant() TO axion_audit;

-- The service switches to it with SET ROLE, as to the other module roles (V15).
GRANT axion_audit TO axion_app WITH INHERIT FALSE, SET TRUE;
