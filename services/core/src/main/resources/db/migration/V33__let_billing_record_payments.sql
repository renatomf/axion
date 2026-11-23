-- The billing module's role records payments and reads them (billing design,
-- section 11). A payment never moves to another charge or changes its
-- outcome; the refunds that change amount_refunded bring their own grant.
GRANT SELECT, INSERT ON billing.payments TO finaxis_billing;
