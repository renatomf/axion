-- Row-Level Security on the idempotency keys (idempotency design, section 5;
-- tenancy design, section 5.4): the module role reads and inserts only the
-- keys of the tenant bound to its transaction, and with no tenant bound, none
-- at all. Two tenants may then use the same key without effect on each other.
-- FORCE applies the policies to the table's owner too, and a role no policy
-- names sees no row; only a superuser is exempt.
ALTER TABLE idempotency.keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency.keys FORCE ROW LEVEL SECURITY;

-- For the module role only, unlike the other modules' tenant policies:
-- policies of one table combine with OR, so a tenant bound by mistake would
-- otherwise show the removal job's role the tenant's keys still honored.
CREATE POLICY tenant_isolation ON idempotency.keys
    TO axion_idempotency
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

-- A policy runs with the privileges of the role querying the table.
GRANT USAGE ON SCHEMA shared TO axion_idempotency;
GRANT EXECUTE ON FUNCTION shared.current_tenant() TO axion_idempotency;
