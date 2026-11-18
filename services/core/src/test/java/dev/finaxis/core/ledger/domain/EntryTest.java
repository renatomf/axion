package dev.finaxis.core.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.shared.Money;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntryTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency USD = Currency.getInstance("USD");

    private static final Account SETTLEMENT = account("platform:settlement", AccountType.ASSET, BRL);
    private static final Account AVAILABLE = account("tenant:1:available", AccountType.LIABILITY, BRL);
    private static final Account PARTNER = account("tenant:2:available", AccountType.LIABILITY, BRL);
    private static final Account FEES = account("platform:fees", AccountType.REVENUE, BRL);

    /** The settlement example of the overview, section 6.4: R$ 100.00 split 70 / 27 / 3. */
    @Test
    void settlementChangesEveryBalanceInItsNormalDirection() {
        Entry entry = entry(
                Posting.debit(SETTLEMENT, brl(10000)),
                Posting.credit(AVAILABLE, brl(7000)),
                Posting.credit(PARTNER, brl(2700)),
                Posting.credit(FEES, brl(300)));

        assertThat(entry.balanceChanges())
                .containsExactly(
                        change(SETTLEMENT.id(), brl(10000)),
                        change(AVAILABLE.id(), brl(7000)),
                        change(PARTNER.id(), brl(2700)),
                        change(FEES.id(), brl(300)));
    }

    @Test
    void postingsToTheSameAccountAreAddedTogether() {
        Entry entry = entry(
                Posting.debit(SETTLEMENT, brl(500)),
                Posting.debit(SETTLEMENT, brl(500)),
                Posting.credit(AVAILABLE, brl(1000)));

        assertThat(entry.balanceChanges())
                .containsExactly(change(SETTLEMENT.id(), brl(1000)), change(AVAILABLE.id(), brl(1000)));
    }

    @Test
    void rejectsAnUnbalancedEntry() {
        assertThatThrownBy(() -> entry(Posting.debit(SETTLEMENT, brl(10000)), Posting.credit(AVAILABLE, brl(9999))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced in BRL")
                .hasMessageContaining("BRL 0.01");
    }

    /** Each currency balances on its own: a BRL debit cannot be paid by a USD credit. */
    @Test
    void rejectsAnEntryThatBalancesOnlyAcrossCurrencies() {
        Account dollars = account("platform:usd", AccountType.LIABILITY, USD);

        assertThatThrownBy(() ->
                        entry(Posting.debit(SETTLEMENT, brl(1000)), Posting.credit(dollars, Money.ofMinor(1000, USD))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    void rejectsAnEntryWithFewerThanTwoPostings() {
        assertThatThrownBy(() -> new Entry(newId(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry(Posting.debit(SETTLEMENT, brl(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not 1");
    }

    @Test
    void acceptsUpToTheMaximumNumberOfPostings() {
        List<Posting> postings =
                new ArrayList<>(Collections.nCopies(Entry.MAX_POSTINGS - 1, Posting.credit(AVAILABLE, brl(1))));
        postings.add(Posting.debit(SETTLEMENT, brl(Entry.MAX_POSTINGS - 1)));

        assertThat(new Entry(newId(), postings).postings()).hasSize(Entry.MAX_POSTINGS);

        postings.add(Posting.debit(SETTLEMENT, brl(1)));
        postings.add(Posting.credit(AVAILABLE, brl(1)));
        assertThatThrownBy(() -> new Entry(newId(), postings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not " + (Entry.MAX_POSTINGS + 2));
    }

    @Test
    void changingTheCallersListDoesNotChangeTheEntry() {
        List<Posting> postings =
                new ArrayList<>(List.of(Posting.debit(SETTLEMENT, brl(100)), Posting.credit(AVAILABLE, brl(100))));
        Entry entry = new Entry(newId(), postings);

        postings.add(Posting.debit(SETTLEMENT, brl(1)));

        assertThat(entry.postings()).hasSize(2);
    }

    @Test
    void reversalPostsEveryLegInTheOppositeDirectionAndLinksTheOriginal() {
        Entry original = entry(Posting.debit(SETTLEMENT, brl(10000)), Posting.credit(AVAILABLE, brl(10000)));
        EntryId reversalId = newId();

        Entry reversal = original.reversal(reversalId);

        assertThat(original.reverses()).isEmpty();
        assertThat(reversal.id()).isEqualTo(reversalId);
        assertThat(reversal.reverses()).contains(original.id());
        assertThat(reversal.postings())
                .containsExactly(Posting.credit(SETTLEMENT, brl(10000)), Posting.debit(AVAILABLE, brl(10000)));
        assertThat(reversal.balanceChanges())
                .containsExactly(change(SETTLEMENT.id(), brl(-10000)), change(AVAILABLE.id(), brl(-10000)));
    }

    @Test
    void rejectsAnEntryThatReversesItself() {
        Entry original = entry(Posting.debit(SETTLEMENT, brl(100)), Posting.credit(AVAILABLE, brl(100)));

        assertThatThrownBy(() -> original.reversal(original.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
    }

    private static Entry entry(Posting... postings) {
        return new Entry(newId(), List.of(postings));
    }

    private static Map.Entry<AccountId, Money> change(AccountId account, Money change) {
        return Map.entry(account, change);
    }

    private static EntryId newId() {
        return new EntryId(UUID.randomUUID());
    }

    private static Money brl(long minorUnits) {
        return Money.ofMinor(minorUnits, BRL);
    }

    private static Account account(String code, AccountType type, Currency currency) {
        return new Account(new AccountId(UUID.randomUUID()), UUID.randomUUID(), code, type, currency, false, false);
    }
}
