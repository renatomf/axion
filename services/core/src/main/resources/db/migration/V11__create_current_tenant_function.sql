-- The tenant the transaction acts for, which every tenant policy compares
-- against (tenancy design, section 5.4). The service sets finaxis.tenant_id
-- with set_config(..., true) at the start of each transaction.
--
-- current_setting(..., true) returns null for a setting never made, and an
-- empty string once a transaction-local setting has ended; nullif turns both
-- into null. With no tenant, tenant_id = null is never true, so a policy
-- shows no rows and accepts no inserts.
CREATE SCHEMA shared;

CREATE FUNCTION shared.current_tenant() RETURNS uuid
    LANGUAGE sql
    STABLE
    RETURN nullif(current_setting('finaxis.tenant_id', true), '')::uuid;

-- A policy runs with the privileges of the role querying the table. Each
-- module role is granted the schema with its first tenant policy.
REVOKE ALL ON FUNCTION shared.current_tenant() FROM PUBLIC;
GRANT USAGE ON SCHEMA shared TO finaxis_access;
GRANT EXECUTE ON FUNCTION shared.current_tenant() TO finaxis_access;
