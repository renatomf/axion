-- The webhook endpoints tenants register, and the secrets that sign the
-- requests sent to them (events and webhooks design, sections 6 and 11).
--
-- tenant_id names a tenant of the tenancy module. Like every reference
-- across modules, it carries no foreign key.
CREATE SCHEMA webhooks;

CREATE TABLE webhooks.endpoints (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    -- Validated by the module before it is stored (section 7.1); the check
    -- keeps out only what no validation could have accepted.
    url              text        NOT NULL CHECK (char_length(url) <= 2048 AND url LIKE 'https://%'),
    description      text        NULL CHECK (char_length(description) BETWEEN 1 AND 140),
    -- One to 20 public event types, or {*} alone for every type. The types
    -- themselves are the catalog's, checked by the module: a new type needs
    -- no migration. An array holding a null fails array_position's test.
    enabled_events   text[]      NOT NULL CHECK (
                         cardinality(enabled_events) BETWEEN 1 AND 20
                         AND array_position(enabled_events, NULL) IS NULL
                         AND (enabled_events = ARRAY['*'] OR array_position(enabled_events, '*') IS NULL)),
    status           text        NOT NULL CHECK (status IN ('ENABLED', 'DISABLED')),
    -- Why a disabled endpoint receives nothing: set exactly when it is disabled.
    disabled_reason  text        NULL CHECK (disabled_reason IN ('FAILING', 'BY_TENANT')),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CHECK ((status = 'DISABLED') = (disabled_reason IS NOT NULL))
);

-- A tenant's endpoints, newest first, and the count of its limit.
CREATE INDEX endpoints_tenant_id_id_idx ON webhooks.endpoints (tenant_id, id);

-- A secret is kept encrypted, never hashed: HMAC needs the secret itself to
-- sign (section 6.2). The ciphertext is Tink AEAD with the endpoint's ID as
-- associated data, so a ciphertext copied to another endpoint does not
-- decrypt. The key that encrypts it never reaches the database.
CREATE TABLE webhooks.secrets (
    id           uuid        PRIMARY KEY,
    endpoint_id  uuid        NOT NULL REFERENCES webhooks.endpoints,
    tenant_id    uuid        NOT NULL,
    ciphertext   bytea       NOT NULL,
    created_at   timestamptz NOT NULL
);

-- An endpoint's secrets, for signing and for the foreign key's checks.
CREATE INDEX secrets_endpoint_id_idx ON webhooks.secrets (endpoint_id);
