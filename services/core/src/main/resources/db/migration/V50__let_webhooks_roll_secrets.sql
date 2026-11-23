-- Signing secrets roll (events and webhooks design, sections 6.2 and 11).
-- Rolling issues a new secret and keeps the old one signing until its
-- expires_at, the end of its grace period; the current secret has none.
ALTER TABLE webhooks.secrets
    ADD COLUMN expires_at timestamptz NULL,
    ADD CHECK (expires_at >= created_at);

-- One current secret per endpoint: a roll ends the current one before it
-- inserts the next.
CREATE UNIQUE INDEX secrets_current_idx ON webhooks.secrets (endpoint_id) WHERE expires_at IS NULL;

-- A roll reads when an endpoint's secrets end and sets the end of the
-- current one. The role still reads no ciphertext and changes none.
GRANT SELECT (id, endpoint_id, tenant_id, expires_at) ON webhooks.secrets TO axion_webhooks;
GRANT UPDATE (expires_at) ON webhooks.secrets TO axion_webhooks;
