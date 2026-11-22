package dev.finaxis.core.billing;

/** Why a charge refused a refund (billing design, section 10.2), in the order the rules are checked. */
public enum RefundRefusal {
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
    REFUND_LIMIT_REACHED
}
