-- The role the ledger's code uses (ledger design, sections 4 and 5.4). It may
-- only read and insert accounts, entries and postings, and update balances,
-- the one mutable table. The migration role owns the tables; the service
-- never connects as it.
--
-- The role cannot log in yet: the service switches to per-module roles with
-- their own credentials together with Row-Level Security (roadmap, PR 1.22).
-- Until then, tests check its privileges with SET ROLE.

-- Roles belong to the whole PostgreSQL cluster, not to one database, so the
-- role may already exist when another database of the cluster was migrated.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'axion_ledger') THEN
        CREATE ROLE axion_ledger NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA ledger TO axion_ledger;
GRANT SELECT, INSERT ON ledger.accounts, ledger.entries, ledger.postings TO axion_ledger;
GRANT SELECT, INSERT, UPDATE ON ledger.balances TO axion_ledger;

-- A transaction stuck behind a lock fails fast instead of holding a
-- connection; the 30-second bound on a whole transaction is what keeps
-- hot-account folding exact (section 5.3). Applied when the role logs in.
ALTER ROLE axion_ledger SET lock_timeout = '2s';
ALTER ROLE axion_ledger SET statement_timeout = '5s';
ALTER ROLE axion_ledger SET transaction_timeout = '30s';
