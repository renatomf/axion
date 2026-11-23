package dev.axion.core.ledger.domain;

import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.AccountType;
import java.util.Currency;
import java.util.UUID;

/** A named bucket of money in one currency (ledger design, section 3.1). Never changed once created. */
public record Account(
        AccountId id,
        UUID tenantId,
        String code,
        AccountType type,
        Currency currency,
        boolean allowNegative,
        boolean hot) {

    public Account {
        if (code.isBlank()) {
            throw new IllegalArgumentException("Account code must not be blank");
        }
        // A hot balance is not checked at posting, so nothing could stop it going negative.
        if (hot && !allowNegative) {
            throw new IllegalArgumentException("Hot account " + code + " must allow a negative balance");
        }
    }
}
