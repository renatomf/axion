package dev.finaxis.core.ledger;

import java.util.Currency;
import java.util.UUID;

/**
 * An account to open; its terms never change afterwards.
 *
 * @param code unique per tenant, such as {@code tenant:{id}:available}; IDs only, never personal data
 * @param allowNegative only the platform's own accounts allow it
 * @param hot balance updated by a scheduled job, not in every posting; must allow negative
 */
public record AccountRequest(
        UUID tenantId, String code, AccountType type, Currency currency, boolean allowNegative, boolean hot) {}
