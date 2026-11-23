package dev.axion.core.ledger;

import dev.axion.core.ledger.domain.Account;
import dev.axion.core.shared.Money;
import java.util.Currency;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Amounts, accounts and sources for the ledger's tests. */
public final class LedgerFixtures {

    public static final Currency BRL = Currency.getInstance("BRL");

    private LedgerFixtures() {}

    public static Money brl(long minorUnits) {
        return Money.ofMinor(minorUnits, BRL);
    }

    /** An account in memory only, for the domain tests. */
    public static Account account(String code, AccountType type) {
        return account(code, type, BRL);
    }

    public static Account account(String code, AccountType type, Currency currency) {
        return new Account(new AccountId(UUID.randomUUID()), UUID.randomUUID(), code, type, currency, false, false);
    }

    public static EntrySource newSource() {
        return new EntrySource("charge", "ch_" + UUID.randomUUID(), "settlement");
    }

    /** The balance row as stored, read without the ledger's own code. */
    public static long storedBalance(JdbcClient jdbc, AccountId account) {
        return jdbc.sql("SELECT balance FROM ledger.balances WHERE account_id = ?")
                .param(account.value())
                .query(Long.class)
                .single();
    }
}
