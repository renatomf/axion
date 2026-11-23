-- The role the tenancy module's code uses (tenancy design, section 5.6). It
-- may create organizations, tenants and members, and grant and revoke roles;
-- it can never update or delete an organization, tenant or member, so it
-- cannot make an organization the platform or move a tenant to another
-- organization. Changing a tenant's status gets its privilege with the
-- feature that needs it.
--
-- Like axion_ledger, the role cannot log in until per-module roles arrive
-- with Row-Level Security (roadmap, PR 1.22); tests use SET ROLE.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_tenancy') THEN
        CREATE ROLE axion_tenancy NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA tenancy TO axion_tenancy;
GRANT SELECT, INSERT ON tenancy.organizations, tenancy.tenants, tenancy.members TO axion_tenancy;
GRANT SELECT, INSERT, DELETE ON tenancy.member_roles TO axion_tenancy;

-- The same bounds as the ledger: authentication reads these tables on every
-- request, so a stuck statement must fail fast instead of holding a connection.
ALTER ROLE axion_tenancy SET lock_timeout = '2s';
ALTER ROLE axion_tenancy SET statement_timeout = '5s';
ALTER ROLE axion_tenancy SET transaction_timeout = '30s';
