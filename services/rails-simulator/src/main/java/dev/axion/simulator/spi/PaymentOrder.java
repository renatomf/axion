package dev.axion.simulator.spi;

import org.jspecify.annotations.Nullable;

/**
 * What a payer asks their institution to pay: a BR Code, read, with who pays and what they write.
 *
 * @param amountMinor the code's amount in centavos, positive
 * @param transactionId the code's transaction ID
 * @param pixKey the code's Pix key, if it carries one
 * @param brCode the code as the payer read it
 * @param debtor the payer
 * @param remittanceInformation the payer's message, if any
 */
public record PaymentOrder(
        long amountMinor,
        String transactionId,
        @Nullable String pixKey,
        String brCode,
        Debtor debtor,
        @Nullable String remittanceInformation) {}
