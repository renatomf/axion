-- Row-Level Security on the ledger (tenancy design, section 5.5). A tenant
-- reads and writes its own rows; it also reads the platform's accounts, and
-- writes and reads the platform legs of its own entries, as a settlement
-- posts them. With no tenant bound, it sees and writes nothing. The ledger
-- role's grants (V5) already limit which statements it may run; USING filters
-- what they see and WITH CHECK rejects a row written for another tenant.
ALTER TABLE ledger.accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger.entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.entries FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger.postings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.postings FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger.balances ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.balances FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON ledger.accounts
    USING (tenant_id IN (shared.current_tenant(), shared.current_platform_tenant()))
    WITH CHECK (tenant_id = shared.current_tenant());

CREATE POLICY tenant_isolation ON ledger.entries
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

-- A posting carries its account's tenant; its entry's tenant is the one the
-- entry was posted for. The subqueries read entries under their own policy.
CREATE POLICY tenant_isolation ON ledger.postings
    USING (tenant_id = shared.current_tenant()
           OR (tenant_id = shared.current_platform_tenant()
               AND entry_id IN (SELECT id FROM ledger.entries WHERE tenant_id = shared.current_tenant())))
    WITH CHECK (tenant_id IN (shared.current_tenant(), shared.current_platform_tenant())
                AND entry_id IN (SELECT id FROM ledger.entries WHERE tenant_id = shared.current_tenant()));

-- The platform's accounts are hot, so a tenant's posting never changes their
-- balance rows (ledger design, section 5.3).
CREATE POLICY tenant_isolation ON ledger.balances
    USING (account_id IN (SELECT id FROM ledger.accounts WHERE tenant_id = shared.current_tenant()))
    WITH CHECK (account_id IN (SELECT id FROM ledger.accounts WHERE tenant_id = shared.current_tenant()));

-- The balance verifier reads every tenant's rows (V16), and the commit-time
-- checks every posting of the entry they check (V14). Their grants already
-- limit them to reading; these policies let them read across tenants.
CREATE POLICY ledger_jobs ON ledger.accounts FOR SELECT TO finaxis_ledger_jobs USING (true);
CREATE POLICY ledger_jobs ON ledger.entries FOR SELECT TO finaxis_ledger_jobs USING (true);
CREATE POLICY ledger_jobs ON ledger.postings FOR SELECT TO finaxis_ledger_jobs USING (true);
CREATE POLICY ledger_jobs ON ledger.balances FOR SELECT TO finaxis_ledger_jobs USING (true);

CREATE POLICY ledger_checks ON ledger.postings FOR SELECT TO finaxis_ledger_checks USING (true);
