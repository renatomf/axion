-- Row-Level Security on the risk counters (billing design, section 11;
-- tenancy design, section 5.4): the module role reads, creates and adds to
-- only the windows of the tenant bound to its transaction, and with no tenant
-- bound, none at all. FORCE applies the policies to the table's owner too,
-- and a role no policy names sees no row; only a superuser is exempt.
ALTER TABLE risk.charge_volume ENABLE ROW LEVEL SECURITY;
ALTER TABLE risk.charge_volume FORCE ROW LEVEL SECURITY;

-- For the module role only, as for the idempotency keys (V21): policies of one
-- table combine with OR, so a tenant bound by mistake would otherwise widen
-- what the removal job's role sees.
CREATE POLICY tenant_isolation ON risk.charge_volume
    TO finaxis_risk
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

-- A policy runs with the privileges of the role querying the table.
GRANT USAGE ON SCHEMA shared TO finaxis_risk;
GRANT EXECUTE ON FUNCTION shared.current_tenant() TO finaxis_risk;
