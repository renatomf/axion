-- The replays a tenant asked for: one delivery sent again, now, with the
-- same body and a fresh signature (events and webhooks design, section 8.7).
-- A replay is PENDING until a worker sends it (SENT) or its endpoint stops
-- receiving first (CANCELED); a worker holds a pending one with a lease, as
-- it holds a delivery (section 8.2). Replays sent and canceled stay until
-- their delivery goes, since they count toward its daily limit.
CREATE TABLE webhooks.replays (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    delivery_id   uuid        NOT NULL REFERENCES webhooks.deliveries,
    status        text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'CANCELED')),
    requested_at  timestamptz NOT NULL,
    -- Until when a worker holds the replay; only a pending one is held.
    leased_until  timestamptz NULL,
    CHECK (leased_until IS NULL OR status = 'PENDING')
);

-- A delivery's replays in the order they were asked for: what its daily
-- limit counts.
CREATE INDEX replays_delivery_idx ON webhooks.replays (delivery_id, requested_at);

ALTER TABLE webhooks.replays ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhooks.replays FORCE ROW LEVEL SECURITY;

-- The module role queues a replay for the tenant bound, and reads when its
-- delivery's earlier replays were asked for.
GRANT INSERT, SELECT (tenant_id, delivery_id, requested_at) ON webhooks.replays TO axion_webhooks;

-- Row-Level Security, as on the deliveries (V53): the role reads only the
-- replays of the tenant bound to its transaction, and with none bound, none.
CREATE POLICY tenant_isolation ON webhooks.replays
    FOR SELECT TO axion_webhooks
    USING (tenant_id = shared.current_tenant());

-- It queues a pending replay only, of a delivery it can see: one of the
-- tenant bound, which a foreign key alone would not check.
CREATE POLICY replays_queueable ON webhooks.replays
    FOR INSERT TO axion_webhooks
    WITH CHECK (tenant_id = shared.current_tenant() AND status = 'PENDING' AND leased_until IS NULL
                AND EXISTS (SELECT 1 FROM webhooks.deliveries AS delivery
                             WHERE delivery.id = replays.delivery_id));
