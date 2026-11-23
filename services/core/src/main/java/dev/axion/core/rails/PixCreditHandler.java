package dev.axion.core.rails;

/**
 * Where the rails module hands each credit of its inbox (billing design, section 7.3). The module
 * defines the port and billing implements it, so the rails never depend on billing.
 *
 * <p>A credit is handed over at least once: after a crash, or when a lease expires, the same credit
 * comes again, and the handler answers {@link CreditResult#ALREADY_RECORDED} without a second
 * effect. An exception leaves the credit to be handed over again later.
 */
public interface PixCreditHandler {

    /** Records the credit, or recognizes it as recorded already; committed when this returns. */
    CreditResult handle(PixCredit credit);
}
