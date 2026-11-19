package dev.finaxis.core.ledger;

import dev.finaxis.core.shared.Money;
import java.time.Instant;

/**
 * An account's balance as {@link LedgerApi#balanceOf} read it (ledger design, section 6).
 *
 * @param account the account
 * @param balance the balance in the account's currency, signed by its normal balance: positive when
 *     the account holds what its type says it holds, such as money a tenant is owed
 * @param readAt when the balance was read; a later posting may already have changed it
 */
public record Balance(AccountId account, Money balance, Instant readAt) {}
