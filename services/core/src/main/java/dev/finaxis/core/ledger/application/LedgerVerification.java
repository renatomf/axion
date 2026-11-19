package dev.finaxis.core.ledger.application;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.EntryId;
import java.util.Currency;
import java.util.List;

/**
 * What one run of the {@link BalanceVerifier} found: the entries, accounts and currencies that
 * break an invariant (ledger design, section 7). Every list is empty in a sound ledger.
 *
 * @param unbalancedEntries entries with fewer than two postings or unequal debits and credits (I1)
 * @param driftedBalances accounts whose balance differs from the sum of their postings (I6)
 * @param unbalancedCurrencies currencies whose total debits differ from their total credits (I7)
 */
public record LedgerVerification(
        List<EntryId> unbalancedEntries, List<AccountId> driftedBalances, List<Currency> unbalancedCurrencies) {

    public LedgerVerification {
        unbalancedEntries = List.copyOf(unbalancedEntries);
        driftedBalances = List.copyOf(driftedBalances);
        unbalancedCurrencies = List.copyOf(unbalancedCurrencies);
    }

    /** Whether every check passed. */
    public boolean passed() {
        return unbalancedEntries.isEmpty() && driftedBalances.isEmpty() && unbalancedCurrencies.isEmpty();
    }
}
