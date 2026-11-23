package dev.axion.core.ledger.domain;

import dev.axion.core.ledger.Direction;
import dev.axion.core.shared.Money;

/** One leg of an entry: a positive amount (invariant I5) in the account's currency. */
public record Posting(Account account, Direction direction, Money amount) {

    public Posting {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Posting amount must be positive: " + amount);
        }
        if (!amount.currency().equals(account.currency())) {
            throw new IllegalArgumentException(
                    "Posting in " + amount.currency() + " to account " + account.code() + " in " + account.currency());
        }
    }

    public static Posting debit(Account account, Money amount) {
        return new Posting(account, Direction.DEBIT, amount);
    }

    public static Posting credit(Account account, Money amount) {
        return new Posting(account, Direction.CREDIT, amount);
    }

    /** Positive in the account's normal balance direction: a debit raises an asset, lowers a liability. */
    public Money balanceEffect() {
        return direction == account.type().normalBalance() ? amount : amount.negate();
    }

    public Posting reversed() {
        return new Posting(account, direction.opposite(), amount);
    }
}
