package dev.finaxis.core.ledger;

/**
 * The accounting nature of an account, which fixes its normal balance (ledger design, section
 * 3.2). A tenant's available balance is a liability: money the platform owes.
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

    public Direction normalBalance() {
        return normalBalance;
    }
}
