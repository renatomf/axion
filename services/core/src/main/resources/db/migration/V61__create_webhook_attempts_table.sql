-- Every request sent for a delivery, and what came of it: the delivery log
-- (events and webhooks design, sections 8.7 and 11). An attempt got either a
-- status code or a failure that left none to judge, and only an answer has a
-- body to keep: at most 1 KiB of it, as text.
--
-- The log is evidence of what Axion sent, so it is append-only: no role may
-- update an attempt, and none may delete one.
CREATE TABLE webhooks.attempts (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    delivery_id       uuid        NOT NULL REFERENCES webhooks.deliveries,
    started_at        timestamptz NOT NULL,
    duration_ms       integer     NOT NULL CHECK (duration_ms >= 0),
    status_code       integer     NULL CHECK (status_code BETWEEN 100 AND 599),
    failure           text        NULL CHECK (failure IN ('TIMEOUT', 'CONNECTION_REFUSED', 'ADDRESS_REFUSED',
                                                          'UNRESOLVED', 'TLS_ERROR', 'REDIRECT',
                                                          'CONNECTION_FAILED')),
    response_excerpt  text        NULL CHECK (char_length(response_excerpt) <= 1024),
    CONSTRAINT attempts_outcome_check CHECK ((status_code IS NULL) <> (failure IS NULL)),
    CONSTRAINT attempts_excerpt_check CHECK (response_excerpt IS NULL OR status_code IS NOT NULL)
);

-- A delivery's attempts, in the order they were made.
CREATE INDEX attempts_delivery_idx ON webhooks.attempts (delivery_id, started_at);

ALTER TABLE webhooks.attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhooks.attempts FORCE ROW LEVEL SECURITY;

-- The workers record each request they send, across tenants, under the
-- tenant of its delivery: a bug in their SQL cannot file an attempt in
-- another tenant's log. They never read the log back.
GRANT SELECT (tenant_id) ON webhooks.deliveries TO axion_webhooks_jobs;
GRANT INSERT ON webhooks.attempts TO axion_webhooks_jobs;

CREATE POLICY attempts_recordable ON webhooks.attempts
    FOR INSERT TO axion_webhooks_jobs
    WITH CHECK (tenant_id = (SELECT delivery.tenant_id FROM webhooks.deliveries AS delivery
                              WHERE delivery.id = attempts.delivery_id));
