-- The workers that send webhooks sign each request with its endpoint's
-- secrets (events and webhooks design, section 8.4), so axion_webhooks_jobs
-- reads the ciphertext of the secrets that still sign: the current one and
-- a rolled one in its grace period. It decrypts them with a key the
-- database never holds. The ended secrets it removes stay visible to it
-- too (V51), for the minute before they are gone. It changes no secret.
GRANT SELECT (endpoint_id, ciphertext) ON webhooks.secrets TO axion_webhooks_jobs;

CREATE POLICY signing_secrets_visible ON webhooks.secrets
    FOR SELECT TO axion_webhooks_jobs
    USING (expires_at IS NULL OR expires_at > now());
