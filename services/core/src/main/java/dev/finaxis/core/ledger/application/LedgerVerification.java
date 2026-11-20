package dev.finaxis.core.ledger.application;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.EntryId;
import java.util.Currency;
import java.util.List;

/**
 * What one run of the {@link BalanceVerifier} found; every list is empty in a sound ledger.
 *
 * @param unbalancedEntries I1
 * @param driftedBalances I6
 * @param unbalancedCurrencies I7
 */
public record LedgerVerification(
        List<EntryId> unbalancedEntries, List<AccountId> driftedBalances, List<Currency> unbalancedCurrencies) {

    public LedgerVerification {
        unbalancedEntries = List.copyOf(unbalancedEntries);
        driftedBalances = List.copyOf(driftedBalances);
        unbalancedCurrencies = List.copyOf(unbalancedCurrencies);
    }

    public boolean passed() {
        return unbalancedEntries.isEmpty() && driftedBalances.isEmpty() && unbalancedCurrencies.isEmpty();
    }
}
