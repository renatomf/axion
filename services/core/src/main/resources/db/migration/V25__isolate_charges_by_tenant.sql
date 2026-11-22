-- Row-Level Security on the charges (billing design, section 11; tenancy
-- design, section 5.4): the module role reads, inserts and updates only the
-- charges of the tenant bound to its transaction, and with no tenant bound,
-- none at all. FORCE applies the policies to the table's owner too, and a
-- role no policy names sees no row; only a superuser is exempt.
ALTER TABLE billing.charges ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.charges FORCE ROW LEVEL SECURITY;

-- For the module role only, as for the idempotency keys (V21): policies of one
-- table combine with OR, so a tenant bound by mistake would otherwise widen
-- what the expiry job's and the transaction ID lookup's roles see.
CREATE POLICY tenant_isolation ON billing.charges
    TO finaxis_billing
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

-- A policy runs with the privileges of the role querying the table.
GRANT USAGE ON SCHEMA shared TO finaxis_billing;
GRANT EXECUTE ON FUNCTION shared.current_tenant() TO finaxis_billing;
