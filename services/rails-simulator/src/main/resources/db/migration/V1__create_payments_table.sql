-- Pix payments the simulated payers' institutions have settled (rails
-- simulator design, section 8.3). A row is written once, when the payment
-- settles, and never changed. The payer's personal data is not kept here.
CREATE TABLE payments (
    -- The payment's identity on both sides, in the format of section 4.3:
    -- E, the payer institution's ISPB, the UTC minute and 11 letters or digits.
    end_to_end_id  char(32)    PRIMARY KEY
                               CHECK (end_to_end_id ~ '^E[0-9]{8}[0-9]{12}[A-Za-z0-9]{11}$'),
    -- The BR Code's transaction ID, by which the core finds the charge.
    transaction_id text        NOT NULL CHECK (transaction_id ~ '^[A-Za-z0-9]{1,25}$'),
    -- In centavos, the minor unit of the real.
    amount_minor   bigint      NOT NULL CHECK (amount_minor > 0),
    -- The payer's institution.
    debtor_ispb    char(8)     NOT NULL CHECK (debtor_ispb ~ '^[0-9]{8}$'),
    -- The code as the payer read it, for following a payment back to its charge.
    br_code        text        NOT NULL,
    settled_at     timestamptz NOT NULL
);
