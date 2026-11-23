-- Row-Level Security on the API keys (tenancy design, section 5.4): a
-- statement reads, updates and inserts only the keys of the tenant bound to
-- its transaction, and with no tenant bound, none at all. FORCE applies the
-- policies to the table's owner too, so a session that connects as the owner
-- is not exempt by accident; only a superuser is.
ALTER TABLE access.api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE access.api_keys FORCE ROW LEVEL SECURITY;

-- USING filters the rows a statement sees; WITH CHECK rejects a row written
-- for another tenant.
CREATE POLICY tenant_isolation ON access.api_keys
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

-- The lookup functions of V12 run as axion_access_lookup, before a tenant
-- is known. Its grants already limit it to reading keys and setting
-- last_used_at; these policies let it do so for any tenant.
CREATE POLICY key_lookup ON access.api_keys
    FOR SELECT TO axion_access_lookup
    USING (true);

CREATE POLICY key_use ON access.api_keys
    FOR UPDATE TO axion_access_lookup
    USING (true)
    WITH CHECK (true);
