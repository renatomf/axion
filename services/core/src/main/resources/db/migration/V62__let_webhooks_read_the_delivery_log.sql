-- The tenant reads its delivery log through the module role (events and
-- webhooks design, sections 8.7 and 12.2): an endpoint's deliveries, newest
-- first, with the type of the event each sends, and each delivery's attempts.
-- The role still never reads an event's body, nor a secret.
GRANT SELECT (event_id, attempt_count, next_attempt_at, created_at) ON webhooks.deliveries TO axion_webhooks;
GRANT SELECT (type) ON webhooks.events TO axion_webhooks;
GRANT SELECT ON webhooks.attempts TO axion_webhooks;

-- Row-Level Security, as on the deliveries (V53): the role reads only the
-- attempts of the tenant bound to its transaction, and with none bound, none.
CREATE POLICY tenant_isolation ON webhooks.attempts
    FOR SELECT TO axion_webhooks
    USING (tenant_id = shared.current_tenant());

-- An endpoint's deliveries, newest first: what a page of its log reads.
CREATE INDEX deliveries_endpoint_id_idx ON webhooks.deliveries (endpoint_id, id);
