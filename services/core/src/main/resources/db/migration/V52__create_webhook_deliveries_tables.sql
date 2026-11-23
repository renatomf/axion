-- The public events the webhooks module sends, and their deliveries: one
-- event to one endpoint (events and webhooks design, sections 8.1, 8.2 and
-- 11).
--
-- An event is stored as the bytes sent (section 5.1): every attempt of every
-- delivery sends exactly them. Its id is the domain event's, so a listener
-- handed the same event twice finds it and adds nothing (section 4.4).
CREATE TABLE webhooks.events (
    id           uuid        PRIMARY KEY,
    tenant_id    uuid        NOT NULL,
    type         text        NOT NULL,
    body         bytea       NOT NULL,
    occurred_at  timestamptz NOT NULL
);

-- A delivery is PENDING until it succeeds, its attempts run out (DEAD) or
-- its endpoint stops receiving (CANCELED). A worker claims a pending one
-- with a lease, so no transaction stays open while the tenant's server
-- answers (section 8.2).
CREATE TABLE webhooks.deliveries (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    endpoint_id      uuid        NOT NULL REFERENCES webhooks.endpoints,
    event_id         uuid        NOT NULL REFERENCES webhooks.events,
    status           text        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'DEAD', 'CANCELED')),
    attempt_count    integer     NOT NULL CHECK (attempt_count BETWEEN 0 AND 9),
    -- When the next attempt is due: set exactly while the delivery is pending.
    next_attempt_at  timestamptz NULL,
    -- Until when a worker holds the delivery; only a pending one is held.
    leased_until     timestamptz NULL,
    created_at       timestamptz NOT NULL,
    CHECK ((status = 'PENDING') = (next_attempt_at IS NOT NULL)),
    CHECK (leased_until IS NULL OR status = 'PENDING'),
    -- An event is delivered once to each endpoint, however often its
    -- listener is handed it.
    UNIQUE (endpoint_id, event_id)
);

-- The workers find the due deliveries by this index.
CREATE INDEX deliveries_due_idx ON webhooks.deliveries (next_attempt_at) WHERE status = 'PENDING';

-- An endpoint's pending deliveries, oldest first: what a worker claims for
-- it, and what a deleted endpoint cancels.
CREATE INDEX deliveries_endpoint_pending_idx ON webhooks.deliveries (endpoint_id, next_attempt_at)
    WHERE status = 'PENDING';
