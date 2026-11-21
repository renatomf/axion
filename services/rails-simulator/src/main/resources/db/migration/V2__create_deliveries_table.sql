-- The messages owed to the core, one row per delivery, written in the same
-- transaction as the payment they notify (rails simulator design, sections
-- 6.1 and 8.3). The payer's personal data lives only inside `message`.
CREATE TABLE deliveries (
    id              uuid        PRIMARY KEY,
    end_to_end_id   char(32)    NOT NULL REFERENCES payments,
    -- Copied from the message, so logs and the X-Correlation-Id header carry
    -- it without parsing the bytes. The copies of one message share it.
    message_id      char(32)    NOT NULL CHECK (message_id ~ '^M[0-9]{8}[A-Za-z0-9]{23}$'),
    kind            text        NOT NULL CHECK (kind IN ('original', 'duplicate', 'redelivery')),
    -- The exact bytes sent on every attempt.
    message         bytea       NOT NULL,
    status          text        NOT NULL CHECK (status IN ('pending', 'delivered', 'refused', 'failed')),
    attempts        integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    -- When the dispatcher may send it next; only a pending delivery has one.
    next_attempt_at timestamptz NULL,
    created_at      timestamptz NOT NULL,
    CHECK ((status = 'pending') = (next_attempt_at IS NOT NULL))
);

-- The dispatcher's query: the pending deliveries that are due.
CREATE INDEX deliveries_due ON deliveries (next_attempt_at) WHERE status = 'pending';

-- A payment's deliveries, for the payer API.
CREATE INDEX deliveries_end_to_end_id ON deliveries (end_to_end_id);
