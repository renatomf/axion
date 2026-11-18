package dev.finaxis.core.ledger;

import java.util.Currency;
import java.util.UUID;

/**
 * What a caller asks for when it opens an account through {@link LedgerApi#openAccount}. The
 * account's terms never change after it is opened.
 *
 * @param tenantId the tenant that owns the account
 * @param code the account's name, unique per tenant, such as {@code tenant:{id}:available}; IDs
 *     only, never personal data
 * @param type the accounting nature, which fixes the normal balance
 * @param currency the only currency the account's postings may use
 * @param allowNegative whether the balance may go below zero; only the platform's own accounts
 *     allow it
 * @param hot whether the balance is updated by a scheduled job instead of in every posting
 *     transaction; a hot account must allow a negative balance
 */
public record AccountRequest(
        UUID tenantId, String code, AccountType type, Currency currency, boolean allowNegative, boolean hot) {}
