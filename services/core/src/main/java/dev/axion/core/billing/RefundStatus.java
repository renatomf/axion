package dev.axion.core.billing;

/**
 * Where a refund stands. In phase 1 a refund is only a ledger entry, so it succeeds at once; phase 2
 * sends it to the rails as a Pix return and adds the states in between (billing design, section 10.3).
 */
public enum RefundStatus {
    SUCCEEDED
}
