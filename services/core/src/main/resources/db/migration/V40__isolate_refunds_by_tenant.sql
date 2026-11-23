-- Row-Level Security on the refunds, as on the payments (V34): the module
-- role reads and inserts only the refunds of the tenant bound to its
-- transaction, and with no tenant bound, none at all.
ALTER TABLE billing.refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.refunds FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON billing.refunds
    TO finaxis_billing
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());
