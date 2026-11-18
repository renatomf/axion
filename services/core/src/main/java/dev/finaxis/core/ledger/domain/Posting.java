package dev.finaxis.core.ledger.domain;

import dev.finaxis.core.shared.Money;

/**
 * One leg of an entry: an account, a direction and a positive amount (ledger design, section 3.1).
 *
 * <p>The constructor enforces invariant I5, amounts are positive, and that the amount is in the
 * account's currency. A posting that breaks either is a programming error, so it throws instead of
 * returning a result.
 *
 * @param account the account the posting changes
 * @param direction debit or credit
 * @param amount the amount, positive and in the account's currency
 */
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

    /** A debit of the amount to the account. */
    public static Posting debit(Account account, Money amount) {
        return new Posting(account, Direction.DEBIT, amount);
    }

    /** A credit of the amount to the account. */
    public static Posting credit(Account account, Money amount) {
        return new Posting(account, Direction.CREDIT, amount);
    }

    /**
     * How much this posting changes the account's balance (ledger design, section 3.2): the amount
     * when the direction is the account's normal balance, its negation otherwise. A debit of R$ 10.00
     * raises an asset by 10.00 and lowers a liability by 10.00.
     */
    public Money balanceEffect() {
        return direction == account.type().normalBalance() ? amount : amount.negate();
    }

    /** The same leg in the opposite direction, which undoes this one. */
    public Posting reversed() {
        return new Posting(account, direction.opposite(), amount);
    }
}
