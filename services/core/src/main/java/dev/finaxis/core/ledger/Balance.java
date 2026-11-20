package dev.finaxis.core.ledger;

import dev.finaxis.core.shared.Money;
import java.time.Instant;

/**
 * An account's balance at {@code readAt}.
 *
 * @param balance signed by the account's normal balance: positive when it holds what its type says
 */
public record Balance(AccountId account, Money balance, Instant readAt) {}
