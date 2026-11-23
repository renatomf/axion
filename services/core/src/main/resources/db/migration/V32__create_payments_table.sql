-- The payments the charges received (billing design, sections 3.3 and 11):
-- each Pix credit for a charge, recorded once by its EndToEndId with what it
-- did to the charge. Settled or flagged, every payment was credited to the
-- tenant, by the ledger entry it names.
--
-- tenant_id names a tenant of the tenancy module, and entry_id an entry of
-- the ledger. Like every reference across modules, neither carries a foreign
-- key.
CREATE TABLE billing.payments (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    charge_id        uuid        NOT NULL REFERENCES billing.charges,
    -- Unique on the rails: the second barrier against a double credit, behind
    -- the charge's lock (section 8.4). The format of the rails simulator
    -- design, section 4.3.
    end_to_end_id    char(32)    NOT NULL UNIQUE CHECK (end_to_end_id ~ '^E[0-9]{20}[A-Za-z0-9]{11}$'),
    -- In the charge's currency, BRL.
    amount_minor     bigint      NOT NULL CHECK (amount_minor > 0),
    -- What refunds returned of this payment.
    amount_refunded  bigint      NOT NULL,
    -- When the rails settled the payment, which decides whether it was late.
    accepted_at      timestamptz NOT NULL,
    outcome          text        NOT NULL CHECK (outcome IN ('SETTLED', 'FLAGGED')),
    review_reason    text        NULL
        CHECK (review_reason IN ('CHARGE_ALREADY_PAID', 'CHARGE_EXPIRED', 'AMOUNT_MISMATCH')),
    entry_id         uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    CONSTRAINT payments_amount_refunded_check CHECK (amount_refunded BETWEEN 0 AND amount_minor),
    CONSTRAINT payments_review_reason_set_check CHECK ((review_reason IS NOT NULL) = (outcome = 'FLAGGED'))
);

-- A charge's payments, for its detail and for settlement.
CREATE INDEX payments_charge_id_idx ON billing.payments (charge_id);

-- One settling payment per charge, held by the database as well as the domain.
CREATE UNIQUE INDEX payments_settling_idx ON billing.payments (charge_id) WHERE outcome = 'SETTLED';
