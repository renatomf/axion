package dev.axion.core.rails;

/** What a {@link PixCreditHandler} did with a credit. */
public enum CreditResult {
    /** The credit was recorded now. */
    RECORDED,
    /** The credit had been recorded by an earlier attempt; nothing changed. */
    ALREADY_RECORDED,
    /** No charge has the credit's transaction ID; nothing was recorded. */
    UNKNOWN_TRANSACTION
}
