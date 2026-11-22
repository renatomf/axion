-- The charges of the billing module (billing design, section 11). A charge is
-- created ACTIVE with its BR Code in one transaction, and only its status,
-- amount_refunded, paid_at and updated_at change afterwards. The domain allows
-- only the transitions of section 3.2; these constraints repeat what a single
-- row can show of them.
--
-- tenant_id names a tenant of the tenancy module. Like the other modules'
-- tenant_id, it carries no foreign key.
CREATE SCHEMA billing;

CREATE TABLE billing.charges (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    status           text        NOT NULL
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    -- R$ 0.01 to R$ 1,000,000.00 in centavos; each tenant's own limits are risk's.
    amount_minor     bigint      NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    -- The only currency of Pix.
    currency         char(3)     NOT NULL CHECK (currency = 'BRL'),
    -- What refunds returned of the settling payment.
    amount_refunded  bigint      NOT NULL,
    description      text        NULL CHECK (char_length(description) BETWEEN 1 AND 140),
    -- The tenant's own keys and values, stored and returned, never queried.
    metadata         jsonb       NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    -- Unique across tenants: a credit notification names no tenant, only this
    -- ID (section 5.2).
    transaction_id   text        NOT NULL UNIQUE CHECK (transaction_id ~ '^[A-Za-z0-9]{25}$'),
    br_code          text        NOT NULL,
    expires_at       timestamptz NOT NULL,
    -- When the rails accepted the settling payment.
    paid_at          timestamptz NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT charges_amount_refunded_check CHECK (amount_refunded BETWEEN 0 AND amount_minor),
    -- Keeps the edge of expiry, where the core's and the rails' clocks may
    -- disagree, a small part of a charge's life (section 8.2).
    CONSTRAINT charges_lifetime_check CHECK (expires_at >= created_at + interval '1 minute'),
    CONSTRAINT charges_paid_at_check
        CHECK ((paid_at IS NOT NULL) = (status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')))
);

-- A tenant's charges, newest first, and filtered by status (section 12.2).
CREATE INDEX charges_tenant_id_idx ON billing.charges (tenant_id, id);
CREATE INDEX charges_tenant_id_status_idx ON billing.charges (tenant_id, status, id);

-- The expiry job finds the active charges whose expiry passed (section 9).
CREATE INDEX charges_expires_at_idx ON billing.charges (expires_at) WHERE status = 'ACTIVE';
