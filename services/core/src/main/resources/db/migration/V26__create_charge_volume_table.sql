-- The counters behind the risk module's volume rules (billing design,
-- sections 6 and 11): one row per tenant, rule and window, holding the amount
-- and the number of the charges created in it. A charge is added with a
-- conditional UPDATE that holds the row's lock, so two concurrent charges
-- cannot both take the last unit of a limit. A scheduled job removes windows
-- older than 7 days.
--
-- tenant_id names a tenant of the tenancy module. Like the other modules'
-- tenant_id, it carries no foreign key.
CREATE SCHEMA risk;

CREATE TABLE risk.charge_volume (
    tenant_id     uuid        NOT NULL,
    rule          text        NOT NULL CHECK (rule IN ('DAILY_AMOUNT', 'HOURLY_COUNT')),
    -- Midnight of the business date in America/Sao_Paulo for DAILY_AMOUNT,
    -- the start of the clock hour in UTC for HOURLY_COUNT.
    window_start  timestamptz NOT NULL,
    -- Centavos of BRL, the only currency of Pix.
    total_minor   bigint      NOT NULL CHECK (total_minor >= 0),
    count         integer     NOT NULL CHECK (count >= 0),
    PRIMARY KEY (tenant_id, rule, window_start)
);
