-- The idempotency keys of the public API's writes (idempotency design,
-- section 5). A key's row is written in the transaction of the request that
-- ran, together with its response, so a key is either complete or absent:
-- there is no state for a crash to leave behind. Rows are never changed, and a
-- scheduled job removes them once they expire (section 6).
--
-- tenant_id names a tenant of the tenancy module. Like the other modules'
-- tenant_id, it carries no foreign key.
CREATE SCHEMA idempotency;

CREATE TABLE idempotency.keys (
    tenant_id        uuid        NOT NULL,
    -- 1 to 255 visible ASCII characters (section 3.2), compared as opaque text.
    key              text        NOT NULL CHECK (key ~ '^[!-~]{1,255}$'),
    -- SHA-256 of the request's path and body (section 3.3).
    fingerprint      bytea       NOT NULL CHECK (octet_length(fingerprint) = 32),
    -- A 5xx rolled its request back, so it is never stored (section 3.4): a
    -- bug that tried would fail its request instead of making the error the
    -- answer for 24 hours.
    response_status  smallint    NOT NULL CHECK (response_status BETWEEN 200 AND 499),
    content_type     text        NOT NULL CHECK (char_length(content_type) > 0),
    location         text        NULL,
    -- Replayed as written; jsonb would reorder its keys.
    response_body    bytea       NOT NULL CHECK (octet_length(response_body) <= 65536),
    -- Of the request that ran, which its replays are logged with.
    correlation_id   text        NOT NULL,
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    -- The natural key: a row is only ever looked up by tenant and key.
    PRIMARY KEY (tenant_id, key),
    CONSTRAINT keys_retention_check CHECK (expires_at = created_at + interval '24 hours')
);

-- The removal job finds expired rows by this index (section 6.2).
CREATE INDEX keys_expires_at_idx ON idempotency.keys (expires_at);
