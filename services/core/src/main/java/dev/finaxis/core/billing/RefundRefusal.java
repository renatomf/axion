package dev.finaxis.core.billing;

/** Why billing refused a refund (billing design, section 10.2), in the order the rules are checked. */
public enum RefundRefusal {
    /** The charge does not exist, or is another tenant's. */
    CHARGE_NOT_FOUND,
    /** The charge has received no payment. */
    CHARGE_NOT_PAID,
    /** The charge has several payments and the request names none. */
    PAYMENT_REQUIRED,
    /** The named payment is not one of the charge's. */
    UNKNOWN_PAYMENT,
    /** The amount is more than remains of the payment. */
    AMOUNT_EXCEEDS_REFUNDABLE,
    /** The rails accepted the payment more than {@code Charge.REFUND_WINDOW} ago. */
    REFUND_WINDOW_CLOSED,
    /** The charge already has {@code Charge.MAX_REFUNDS} refunds. */
    REFUND_LIMIT_REACHED,
    /** The tenant's available balance is less than the amount; the ledger's to decide. */
    INSUFFICIENT_BALANCE
}
