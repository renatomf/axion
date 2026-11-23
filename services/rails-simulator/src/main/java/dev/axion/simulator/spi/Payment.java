package dev.axion.simulator.spi;

import java.time.Instant;

/**
 * A Pix payment the SPI has settled: the payer was debited and the money reached Axion's settlement
 * account. It is never undone.
 *
 * @param endToEndId the payment's identity on both sides
 * @param transactionId the BR Code's transaction ID
 * @param amountMinor the amount in centavos
 * @param debtorInstitution the payer's institution
 * @param brCode the code the payer paid
 * @param settledAt when the SPI settled it, to the millisecond
 */
public record Payment(
        EndToEndId endToEndId,
        String transactionId,
        long amountMinor,
        Ispb debtorInstitution,
        String brCode,
        Instant settledAt) {}
