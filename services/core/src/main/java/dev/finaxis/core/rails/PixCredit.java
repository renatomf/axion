package dev.finaxis.core.rails;

import dev.finaxis.core.shared.Money;
import java.time.Instant;

/**
 * A Pix credit the rails notified, in the core's terms: what the inbox keeps of the message (billing
 * design, sections 7.2 and 7.3). The rails' format stops here; the payer's name and tax ID are not
 * part of it.
 *
 * @param endToEndId the payment's identity on the rails, unique
 * @param messageId the notification's identity, shared by its copies
 * @param transactionId the BR Code's transaction ID, by which billing finds the charge
 * @param amount what the rails settled, in BRL
 * @param acceptedAt when the rails settled the payment
 * @param debtorIspb the payer's institution
 */
public record PixCredit(
        String endToEndId,
        String messageId,
        String transactionId,
        Money amount,
        Instant acceptedAt,
        String debtorIspb) {}
