-- The role that verifies the audit chains (events and webhooks design,
-- section 9.3). It reads every tenant's records and chain heads and changes
-- nothing: the check cannot change what it checks.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_audit_checks') THEN
        CREATE ROLE axion_audit_checks NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA audit TO axion_audit_checks;
GRANT SELECT ON audit.records, audit.chain_heads TO axion_audit_checks;

-- The job verifies every chain with no tenant bound. Its grants already
-- limit it to reading; these policies let it read across tenants.
CREATE POLICY audit_checks ON audit.records FOR SELECT TO axion_audit_checks USING (true);
CREATE POLICY audit_checks ON audit.chain_heads FOR SELECT TO axion_audit_checks USING (true);

-- The service switches to it with SET ROLE, as to the other module roles (V15).
GRANT axion_audit_checks TO axion_app WITH INHERIT FALSE, SET TRUE;
