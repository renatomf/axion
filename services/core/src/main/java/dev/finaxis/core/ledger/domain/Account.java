package dev.finaxis.core.ledger.domain;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import java.util.Currency;

/**
 * A named bucket of money in one currency, such as {@code tenant:{id}:available} (ledger design,
 * section 3.1). Accounts are configuration: created once and never changed, so the record is
 * immutable.
 *
 * <p>The tenant, the overdraft rule and the hot-account flag join the account with the schema and
 * the balance updates that use them (PRs 1.09 to 1.11).
 *
 * @param id the account's primary key
 * @param code the account's name, unique per tenant; IDs only, never personal data
 * @param type the accounting nature, which fixes the normal balance
 * @param currency the only currency the account's postings may use
 */
public record Account(AccountId id, String code, AccountType type, Currency currency) {

    public Account {
        if (code.isBlank()) {
            throw new IllegalArgumentException("Account code must not be blank");
        }
    }
}
