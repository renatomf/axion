package dev.finaxis.core.rails.web;

import dev.finaxis.core.rails.PixCredit;
import dev.finaxis.core.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The fields the core reads of a credit notification, a {@code pacs.008} of the rails' contract
 * ({@code rails.yaml}; rails simulator design, section 5.2). The others, the payer's name and
 * account among them, are not read at all.
 */
record CreditNotification(
        @Nullable String messageId,
        @Nullable String messageType,
        @Nullable String endToEndId,
        @Nullable String transactionId,
        @Nullable String localInstrument,
        @Nullable Amount interbankSettlementAmount,
        @Nullable String acceptanceDateTime,
        @Nullable Agent debtorAgent,
        @Nullable Agent creditorAgent) {

    record Amount(@Nullable String amount, @Nullable String currency) {}

    record Agent(@Nullable String ispb) {}

    /** A notification that names a field the core needs, missing or malformed. */
    static final class MalformedNotification extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String field;

        MalformedNotification(String field) {
            super("The credit notification's " + field + " is missing or malformed", null, false, false);
            this.field = field;
        }

        /** The field's name in the contract, never its value. */
        String field() {
            return field;
        }
    }

    private static final Pattern MESSAGE_ID = Pattern.compile("M[0-9]{8}[A-Za-z0-9]{23}");
    private static final Pattern END_TO_END_ID = Pattern.compile("E[0-9]{8}[0-9]{12}[A-Za-z0-9]{11}");
    private static final Pattern TRANSACTION_ID = Pattern.compile("[A-Za-z0-9]{1,25}");
    // Up to 15 integer digits, so the centavos fit a long.
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,15}\\.[0-9]{2}");
    private static final Pattern ISPB = Pattern.compile("[0-9]{8}");
    private static final Pattern TIMESTAMP =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");

    private static final Currency BRL = Currency.getInstance("BRL");

    /**
     * The credit this notification carries, once every field the core reads is well formed and it
     * is a credit of a dynamic QR code to {@code receiverIspb} (billing design, section 7.1).
     *
     * @throws MalformedNotification naming the first field that is not
     */
    PixCredit toCredit(String receiverIspb) {
        require("messageType", "pacs.008".equals(messageType));
        require("localInstrument", "QRDN".equals(localInstrument));
        require("creditorAgent.ispb", creditorAgent != null && receiverIspb.equals(creditorAgent.ispb()));
        String message = matching("messageId", messageId, MESSAGE_ID);
        String endToEnd = matching("endToEndId", endToEndId, END_TO_END_ID);
        String transaction = matching("transactionId", transactionId, TRANSACTION_ID);
        String debtor = matching("debtorAgent.ispb", debtorAgent == null ? null : debtorAgent.ispb(), ISPB);
        return new PixCredit(endToEnd, message, transaction, amount(), acceptedAt(), debtor);
    }

    private Money amount() {
        require(
                "interbankSettlementAmount.currency",
                interbankSettlementAmount != null && "BRL".equals(interbankSettlementAmount.currency()));
        String text = matching(
                "interbankSettlementAmount.amount",
                interbankSettlementAmount == null ? null : interbankSettlementAmount.amount(),
                AMOUNT);
        Money amount = Money.of(new BigDecimal(text), BRL);
        require("interbankSettlementAmount.amount", amount.isPositive());
        return amount;
    }

    private Instant acceptedAt() {
        String text = matching("acceptanceDateTime", acceptanceDateTime, TIMESTAMP);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new MalformedNotification("acceptanceDateTime");
        }
    }

    private static String matching(String field, @Nullable String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new MalformedNotification(field);
        }
        return value;
    }

    private static void require(String field, boolean wellFormed) {
        if (!wellFormed) {
            throw new MalformedNotification(field);
        }
    }
}
