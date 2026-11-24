-- The webhooks module records each public event of a tenant and one
-- delivery of it per subscribed endpoint (events and webhooks design,
-- section 8.1).
--
-- An event is inserted once: a duplicate finds its id and adds nothing. ON
-- CONFLICT (id) reads the id, so the role may read that column; it never
-- reads an event's body back, which the queue's role sends.
GRANT INSERT, SELECT (id) ON webhooks.events TO axion_webhooks;
GRANT INSERT ON webhooks.deliveries TO axion_webhooks;

-- Row-Level Security, as on the endpoints (V48): the role reaches only the
-- rows of the tenant bound to its transaction, and with none bound, none.
ALTER TABLE webhooks.events ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhooks.events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON webhooks.events
    TO axion_webhooks
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());

ALTER TABLE webhooks.deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhooks.deliveries FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON webhooks.deliveries
    TO axion_webhooks
    USING (tenant_id = shared.current_tenant())
    WITH CHECK (tenant_id = shared.current_tenant());
