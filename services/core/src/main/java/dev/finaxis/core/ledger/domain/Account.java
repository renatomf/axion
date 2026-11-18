package dev.finaxis.core.ledger.domain;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import java.util.Currency;
import java.util.UUID;

/**
 * A named bucket of money in one currency, such as {@code tenant:{id}:available} (ledger design,
 * section 3.1). Accounts are configuration: created once and never changed, so the record is
 * immutable.
 *
 * @param id the account's primary key
 * @param tenantId the tenant that owns the account; platform accounts belong to a reserved tenant
 * @param code the account's name, unique per tenant; IDs only, never personal data
 * @param type the accounting nature, which fixes the normal balance
 * @param currency the only currency the account's postings may use
 * @param allowNegative whether the balance may go below zero; only the platform's own accounts
 *     allow it
 * @param hot whether the balance is updated by a scheduled job instead of in every posting
 *     transaction, for accounts in almost every entry (ledger design, section 5.3)
 */
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
        // A hot account's balance is not checked when an entry is posted, so nothing could stop it
        // from going negative. The schema repeats this rule.
        if (hot && !allowNegative) {
            throw new IllegalArgumentException("Hot account " + code + " must allow a negative balance");
        }
    }
}
