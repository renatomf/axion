-- Every attempt to send a delivery and what came of it (rails simulator
-- design, sections 4.4 and 12): a payment can be followed to the core, and a
-- disputed one shows how often it was sent. Written once, never changed.
CREATE TABLE delivery_attempts (
    delivery_id     uuid        NOT NULL REFERENCES deliveries,
    number          integer     NOT NULL CHECK (number > 0),
    attempted_at    timestamptz NOT NULL,
    -- The core's status code, or null when no answer came.
    response_status integer     NULL CHECK (response_status BETWEEN 100 AND 599),
    -- Why no answer came: the connection or the response timed out, or failed.
    error           text        NULL CHECK (error IN ('timeout', 'connection_failed')),
    outcome         text        NOT NULL CHECK (outcome IN ('delivered', 'retried', 'refused', 'failed')),
    PRIMARY KEY (delivery_id, number),
    CHECK ((response_status IS NULL) <> (error IS NULL))
);
