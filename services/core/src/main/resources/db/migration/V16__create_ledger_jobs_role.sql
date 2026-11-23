-- The role of the ledger's jobs that span tenants, such as the balance
-- verifier (tenancy design, section 5.4). It may only read the ledger: a job
-- that checks the ledger cannot change what it checks. Once the ledger's
-- tables enforce Row-Level Security, policies written for this role let it
-- read every tenant's rows; no role has BYPASSRLS.

-- Roles belong to the whole PostgreSQL cluster, so the role may already exist.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_ledger_jobs') THEN
        CREATE ROLE axion_ledger_jobs NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA ledger TO axion_ledger_jobs;
GRANT SELECT ON ledger.accounts, ledger.entries, ledger.postings, ledger.balances TO axion_ledger_jobs;

-- The service switches to it with SET ROLE, as to a module role (V15).
GRANT axion_ledger_jobs TO axion_app WITH INHERIT FALSE, SET TRUE;
