package dev.finaxis.core.ledger;

import dev.finaxis.core.shared.Money;

/**
 * One leg of an {@link EntryRequest}: an account, a direction and a positive amount in the
 * account's currency.
 */
public record PostingRequest(AccountId account, Direction direction, Money amount) {

    /** A debit of the amount to the account. */
    public static PostingRequest debit(AccountId account, Money amount) {
        return new PostingRequest(account, Direction.DEBIT, amount);
    }

    /** A credit of the amount to the account. */
    public static PostingRequest credit(AccountId account, Money amount) {
        return new PostingRequest(account, Direction.CREDIT, amount);
    }
}
