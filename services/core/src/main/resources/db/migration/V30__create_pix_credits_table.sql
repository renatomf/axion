-- The inbox of the rails module (billing design, section 7.2): each Pix credit
-- the rails notified and the core verified, recorded once by its EndToEndId
-- before the core answers, then handed to billing by a processor. It keeps
-- what the core needs and the evidence of what it accepted, never the payer's
-- name or tax ID.
--
-- The table holds no tenant: the tenant is known only once billing resolves
-- the transaction ID.
CREATE SCHEMA rails;

CREATE TABLE rails.pix_credits (
    -- E, the payer institution's ISPB, the UTC minute and 11 letters or
    -- digits (rails simulator design, section 4.3). Unique on the rails, so a
    -- duplicated notification is recorded once.
    end_to_end_id    char(32)    PRIMARY KEY CHECK (end_to_end_id ~ '^E[0-9]{20}[A-Za-z0-9]{11}$'),
    -- M, the sender's ISPB and 23 letters or digits; shared by the copies of
    -- one message.
    message_id       char(32)    NOT NULL CHECK (message_id ~ '^M[0-9]{8}[A-Za-z0-9]{23}$'),
    -- The BR Code's transaction ID, by which billing finds the charge.
    transaction_id   text        NOT NULL CHECK (transaction_id ~ '^[A-Za-z0-9]{1,25}$'),
    amount_minor     bigint      NOT NULL CHECK (amount_minor > 0),
    -- The only currency of Pix.
    currency         char(3)     NOT NULL CHECK (currency = 'BRL'),
    -- When the rails settled the payment (acceptanceDateTime).
    accepted_at      timestamptz NOT NULL,
    debtor_ispb      char(8)     NOT NULL CHECK (debtor_ispb ~ '^[0-9]{8}$'),
    -- The signing key that verified the notification.
    key_id           text        NOT NULL CHECK (key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    -- The SHA-256 of the exact bytes accepted, which the rails' copy must match.
    content_digest   bytea       NOT NULL CHECK (octet_length(content_digest) = 32),
    received_at      timestamptz NOT NULL,
    status           text        NOT NULL CHECK (status IN ('received', 'processed', 'unmatched')),
    attempts         integer     NOT NULL CHECK (attempts >= 0),
    next_attempt_at  timestamptz NULL,
    processed_at     timestamptz NULL,
    -- Only a credit still to hand to billing is due; a handled one is done.
    CONSTRAINT pix_credits_next_attempt_at_check CHECK ((next_attempt_at IS NOT NULL) = (status = 'received')),
    CONSTRAINT pix_credits_processed_at_check CHECK ((processed_at IS NOT NULL) = (status <> 'received'))
);

-- The processor claims the received credits that are due (section 7.3).
CREATE INDEX pix_credits_next_attempt_at_idx ON rails.pix_credits (next_attempt_at) WHERE status = 'received';
