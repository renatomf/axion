-- The audit trail (events and webhooks design, section 9): one record per
-- security-relevant action, chained to the record before it by its hash, and
-- the head of each tenant's chain.
--
-- tenant_id names a tenant of the tenancy module. Like every reference
-- across modules, it carries no foreign key.
CREATE SCHEMA audit;

-- A record is hashed over the exact text of its columns (section 9.2), so
-- details is text, not jsonb, which would reorder its keys. No trigger
-- guards the rows: no role but the owner may change them, and the chain
-- shows it if the owner does.
CREATE TABLE audit.records (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    sequence        bigint      NOT NULL CHECK (sequence >= 1),
    occurred_at     timestamptz NOT NULL,
    actor_type      text        NOT NULL CHECK (actor_type IN ('MEMBER', 'API_KEY', 'JOB')),
    actor_id        text        NOT NULL CHECK (char_length(actor_id) BETWEEN 1 AND 64),
    action          text        NOT NULL CHECK (char_length(action) BETWEEN 1 AND 64),
    resource_type   text        NOT NULL CHECK (char_length(resource_type) BETWEEN 1 AND 64),
    resource_id     text        NOT NULL CHECK (char_length(resource_id) BETWEEN 1 AND 64),
    correlation_id  text        NOT NULL CHECK (char_length(correlation_id) BETWEEN 1 AND 128),
    -- A duplicate event finds its record and adds nothing (section 4.4).
    event_id        uuid        NOT NULL UNIQUE,
    details         text        NOT NULL CHECK (jsonb_typeof(details::jsonb) = 'object'),
    -- 32 zero bytes for the first record of a chain.
    previous_hash   bytea       NOT NULL CHECK (octet_length(previous_hash) = 32),
    hash            bytea       NOT NULL CHECK (octet_length(hash) = 32),
    -- A chain is walked in sequence order by this constraint's index.
    UNIQUE (tenant_id, sequence)
);

-- Each chain's last sequence and hash. An append locks its chain's row, so
-- appends to one chain are serialized and appends to different chains are
-- not. A chain starts at sequence 0 with 32 zero bytes, inserted by its
-- first append and moved to that append's record in the same transaction.
CREATE TABLE audit.chain_heads (
    tenant_id  uuid    PRIMARY KEY,
    sequence   bigint  NOT NULL CHECK (sequence >= 0),
    hash       bytea   NOT NULL CHECK (octet_length(hash) = 32)
);

ALTER TABLE audit.records ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.records FORCE ROW LEVEL SECURITY;
ALTER TABLE audit.chain_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.chain_heads FORCE ROW LEVEL SECURITY;
