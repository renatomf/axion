package dev.finaxis.core.ledger;

/**
 * The accounting nature of an account, which fixes its normal balance: the direction in which its
 * balance grows (ledger design, section 3.2).
 *
 * <p>An asset is money the platform holds, such as {@code platform:settlement}; a liability is money
 * it owes, such as a tenant's available balance; revenue is what it earns, such as fees; an expense
 * is what it spends.
 */
public enum AccountType {
    ASSET(Direction.DEBIT),
    LIABILITY(Direction.CREDIT),
    REVENUE(Direction.CREDIT),
    EXPENSE(Direction.DEBIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    /** The direction that increases a balance of this type: debit for assets and expenses, credit otherwise. */
    public Direction normalBalance() {
        return normalBalance;
    }
}
