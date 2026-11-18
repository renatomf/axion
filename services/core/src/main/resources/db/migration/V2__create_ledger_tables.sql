-- The ledger's tables (ledger design, section 3.3). Money is bigint minor
-- units, enumerations are text with a CHECK, and every rule the database can
-- express is repeated here, so a bug or a manual statement cannot break it.
CREATE SCHEMA ledger;

CREATE TABLE ledger.accounts (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    code            text        NOT NULL CHECK (char_length(code) BETWEEN 1 AND 200),
    type            text        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    normal_balance  text        NOT NULL,
    currency        char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    allow_negative  boolean     NOT NULL,
    is_hot          boolean     NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    -- Target of the postings foreign key, so a posting must match its
    -- account's tenant and currency.
    UNIQUE (id, tenant_id, currency),
    -- The type fixes the normal balance (section 3.2).
    CHECK ((type IN ('ASSET', 'EXPENSE') AND normal_balance = 'DEBIT')
        OR (type IN ('LIABILITY', 'REVENUE') AND normal_balance = 'CREDIT')),
    -- A hot account's balance is not checked in the posting transaction, so it
    -- cannot be guarded against going negative (section 5.3).
    CHECK (NOT is_hot OR allow_negative)
);

CREATE TABLE ledger.entries (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    source_type        text        NOT NULL CHECK (char_length(source_type) BETWEEN 1 AND 64),
    source_id          text        NOT NULL CHECK (char_length(source_id) BETWEEN 1 AND 64),
    purpose            text        NOT NULL CHECK (char_length(purpose) BETWEEN 1 AND 64),
    -- Business date in America/Sao_Paulo, from the application's Clock.
    effective_date     date        NOT NULL,
    reverses_entry_id  uuid        NULL REFERENCES ledger.entries CHECK (reverses_entry_id <> id),
    correlation_id     text        NOT NULL CHECK (char_length(correlation_id) BETWEEN 1 AND 128),
    -- Transaction time from the database clock, never the application's.
    created_at         timestamptz NOT NULL DEFAULT now(),
    -- I4: a source fact produces at most one entry per purpose.
    UNIQUE (source_type, source_id, purpose)
);

CREATE TABLE ledger.postings (
    id          uuid        PRIMARY KEY,
    entry_id    uuid        NOT NULL REFERENCES ledger.entries,
    account_id  uuid        NOT NULL,
    -- The account's tenant, for Row-Level Security.
    tenant_id   uuid        NOT NULL,
    currency    char(3)     NOT NULL,
    direction   text        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- I5: amounts are positive minor units; the direction carries the sign.
    amount      bigint      NOT NULL CHECK (amount > 0),
    created_at  timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (account_id, tenant_id, currency) REFERENCES ledger.accounts (id, tenant_id, currency)
);

CREATE INDEX postings_entry_id_idx ON ledger.postings (entry_id);
-- An account's history, newest first, paginated by posting ID.
CREATE INDEX postings_account_id_id_idx ON ledger.postings (account_id, id);

-- The only mutable table: a projection of the postings, created at zero with
-- its account.
CREATE TABLE ledger.balances (
    account_id    uuid        PRIMARY KEY REFERENCES ledger.accounts,
    balance       bigint      NOT NULL DEFAULT 0,
    version       bigint      NOT NULL DEFAULT 0,
    -- Hot accounts only: postings created before this time are in balance.
    folded_until  timestamptz NULL,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
