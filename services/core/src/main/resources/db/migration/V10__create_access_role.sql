-- The role the access module's code uses (tenancy design, section 5.6). It
-- may issue keys and read them, and update only the three columns a key's
-- lifecycle changes (section 4.3): rolling sets expires_at, revoking sets
-- revoked_at, and authentication sets last_used_at. It can never change a
-- key's tenant, scopes or hash, nor delete a key, so a row stays the record
-- of what the key could do and who issued it.
--
-- Like the other module roles, it cannot log in until per-module roles arrive
-- with Row-Level Security (roadmap, PR 1.22); tests use SET ROLE.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_access') THEN
        CREATE ROLE axion_access NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA access TO axion_access;
GRANT SELECT, INSERT ON access.api_keys TO axion_access;
-- A column privilege: an UPDATE that sets any other column is denied.
GRANT UPDATE (expires_at, revoked_at, last_used_at) ON access.api_keys TO axion_access;

-- Authentication reads this table on every /v1 request, so a stuck statement
-- must fail fast instead of holding a connection.
ALTER ROLE axion_access SET lock_timeout = '2s';
ALTER ROLE axion_access SET statement_timeout = '5s';
ALTER ROLE axion_access SET transaction_timeout = '30s';
