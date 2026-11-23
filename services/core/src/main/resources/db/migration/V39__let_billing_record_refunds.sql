-- The billing module's role records refunds and reads them (billing design,
-- section 11). A refund never changes, so the role may not update it; what it
-- returned is added to its payment's amount_refunded, the one column of a
-- payment that changes.
GRANT SELECT, INSERT ON billing.refunds TO finaxis_billing;
GRANT UPDATE (amount_refunded) ON billing.payments TO finaxis_billing;
