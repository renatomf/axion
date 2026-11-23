-- Row-Level Security on the payments, as on the charges (V25): the module
-- role reads and inserts only the payments of the tenant bound to its
-- transaction, and with no tenant bound, none at all.
ALTER TABLE billing.payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.payments FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON billing.payments
    TO axion_billing
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());
