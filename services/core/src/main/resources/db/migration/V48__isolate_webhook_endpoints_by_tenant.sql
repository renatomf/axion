-- Row-Level Security on the endpoints and their secrets (tenancy design,
-- section 5.4): the module role reads and inserts only the rows of the
-- tenant bound to its transaction, and with no tenant bound, none at all.
ALTER TABLE webhooks.endpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhooks.endpoints FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON webhooks.endpoints
    TO axion_webhooks
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

ALTER TABLE webhooks.secrets ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhooks.secrets FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON webhooks.secrets
    TO axion_webhooks
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

-- A policy runs with the privileges of the role querying the table.
GRANT USAGE ON SCHEMA shared TO axion_webhooks;
GRANT EXECUTE ON FUNCTION shared.current_tenant() TO axion_webhooks;
