-- The refunds of the charges' payments (billing design, sections 10 and 11):
-- money given back from one payment, in full or in part, by a ledger entry
-- that reverses all or part of the payment's. A refund never changes; phase 1
-- has only refunds that succeeded, since a refund is only a ledger entry.
--
-- tenant_id names a tenant of the tenancy module, and entry_id an entry of
-- the ledger. Like every reference across modules, neither carries a foreign
-- key.
CREATE TABLE billing.refunds (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    charge_id       uuid        NOT NULL REFERENCES billing.charges,
    payment_id      uuid        NOT NULL REFERENCES billing.payments,
    -- In the charge's currency, BRL.
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
    status          text        NOT NULL CHECK (status IN ('SUCCEEDED')),
    entry_id        uuid        NOT NULL,
    -- Of the request that created the refund, so a disputed refund leads to
    -- its logs.
    correlation_id  text        NOT NULL,
    created_at      timestamptz NOT NULL
);

-- A charge's refunds, for its detail and for each new refund.
CREATE INDEX refunds_charge_id_idx ON billing.refunds (charge_id);

-- A payment's refunds, for the foreign key's checks.
CREATE INDEX refunds_payment_id_idx ON billing.refunds (payment_id);
