-- The API keys of the access module (tenancy design, sections 3.4 and 4.3).
-- The secret itself is never stored: only its SHA-256 hash, so a copy of the
-- table reveals no key. A row is never deleted; it is the record of what the
-- key could do and who issued it.
--
-- tenant_id and created_by name rows of the tenancy module. Like the ledger's
-- tenant_id, they carry no foreign key: modules reach each other only through
-- their APIs, and the access module checks both when it issues a key.
CREATE SCHEMA access;

CREATE TABLE access.api_keys (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    name          text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    -- One indexed lookup finds the key a caller sent (section 4.3).
    secret_hash   bytea       NOT NULL UNIQUE CHECK (octet_length(secret_hash) = 32),
    last_four     char(4)     NOT NULL CHECK (last_four ~ '^[0-9A-Za-z]{4}$'),
    -- At least one scope, and only the permissions an API key may carry
    -- (section 3.3): a human-only permission such as api_keys:manage is
    -- rejected here too. <@ means "is contained in"; an array holding a null
    -- is not contained in any array, so a null scope is rejected as well.
    scopes        text[]      NOT NULL CHECK (
                      cardinality(scopes) > 0
                      AND scopes <@ ARRAY['charges:read', 'charges:write', 'refunds:write',
                                          'balances:read', 'webhooks:manage']::text[]),
    -- The member who issued the key.
    created_by    uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    -- Set when the key is rolled: the end of the grace period.
    expires_at    timestamptz NULL,
    revoked_at    timestamptz NULL,
    -- Updated at most once an hour per key, so busy keys add no write per request.
    last_used_at  timestamptz NULL
);

-- Lists show a tenant's keys, and issuing counts its unrevoked keys.
CREATE INDEX api_keys_tenant_id_idx ON access.api_keys (tenant_id);
