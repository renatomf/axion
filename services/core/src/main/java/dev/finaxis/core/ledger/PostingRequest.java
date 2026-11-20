package dev.finaxis.core.ledger;

import dev.finaxis.core.shared.Money;

/** One leg of an {@link EntryRequest}: a positive amount in the account's currency. */
public record PostingRequest(AccountId account, Direction direction, Money amount) {

    public static PostingRequest debit(AccountId account, Money amount) {
        return new PostingRequest(account, Direction.DEBIT, amount);
    }

    public static PostingRequest credit(AccountId account, Money amount) {
        return new PostingRequest(account, Direction.CREDIT, amount);
    }
}
