package dev.axion.core.ledger.domain;

import static dev.axion.core.ledger.LedgerFixtures.BRL;
import static dev.axion.core.ledger.LedgerFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.Direction;
import dev.axion.core.ledger.EntryId;
import dev.axion.core.shared.Money;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of every ledger entry (ledger design, section 8), checked by jqwik against generated
 * entries of up to {@value Entry#MAX_POSTINGS} postings over accounts of every type.
 */
class EntryPropertiesTest {

    /** Up to about R$ 10 billion per posting, so 31 of them cannot overflow a {@code long}. */
    private static final long MAX_AMOUNT = 1_000_000_000_000L;

    private static final List<Account> ACCOUNTS = List.of(
            account("platform:settlement", AccountType.ASSET),
            account("platform:fees", AccountType.REVENUE),
            account("platform:rails", AccountType.EXPENSE),
            account("tenant:1:available", AccountType.LIABILITY),
            account("tenant:1:held", AccountType.LIABILITY),
            account("tenant:2:available", AccountType.LIABILITY));

    @Property(tries = 1000)
    void balancedEntriesAreAccepted(@ForAll("balancedPostings") List<Posting> postings) {
        Entry entry = new Entry(newId(), postings);

        assertThat(total(entry, Direction.DEBIT)).isEqualTo(total(entry, Direction.CREDIT));
    }

    /** Raising any one posting by any amount leaves the entry unbalanced, and it is rejected. */
    @Property(tries = 1000)
    void unbalancedEntriesAreRejected(
            @ForAll("balancedPostings") List<Posting> postings,
            @ForAll("indexes") int index,
            @ForAll("amounts") Money extra) {
        List<Posting> unbalanced = new ArrayList<>(postings);
        Posting changed = unbalanced.get(index % unbalanced.size());
        unbalanced.set(
                index % unbalanced.size(),
                new Posting(
                        changed.account(), changed.direction(), changed.amount().plus(extra)));

        assertThatThrownBy(() -> new Entry(newId(), unbalanced))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced");
    }

    /** An entry and its reversal together change no balance, on every account the entry touches. */
    @Property(tries = 1000)
    void reversalAndOriginalSumToZeroOnEveryAccount(@ForAll("balancedPostings") List<Posting> postings) {
        Entry original = new Entry(newId(), postings);
        Entry reversal = original.reversal(newId());

        Map<AccountId, Money> net = new HashMap<>(original.balanceChanges());
        reversal.balanceChanges().forEach((account, change) -> net.merge(account, change, Money::plus));

        assertThat(net.keySet()).isEqualTo(original.balanceChanges().keySet());
        assertThat(net.values()).allMatch(Money::isZero);
    }

    /**
     * Any debits and credits, plus one posting on the smaller side for the difference: between 2 and
     * 31 postings that balance by construction, in an order where the balancing leg is last.
     */
    @Provide
    Arbitrary<List<Posting>> balancedPostings() {
        Arbitrary<List<Posting>> debits = legs(Direction.DEBIT);
        Arbitrary<List<Posting>> credits = legs(Direction.CREDIT);
        return Combinators.combine(debits, credits, Arbitraries.of(ACCOUNTS)).as((debitLegs, creditLegs, account) -> {
            List<Posting> postings = new ArrayList<>(debitLegs);
            postings.addAll(creditLegs);
            long difference = sum(debitLegs) - sum(creditLegs);
            if (difference > 0) {
                postings.add(Posting.credit(account, Money.ofMinor(difference, BRL)));
            } else if (difference < 0) {
                postings.add(Posting.debit(account, Money.ofMinor(-difference, BRL)));
            }
            return postings;
        });
    }

    @Provide
    Arbitrary<Money> amounts() {
        return Arbitraries.longs().between(1, MAX_AMOUNT).map(minorUnits -> Money.ofMinor(minorUnits, BRL));
    }

    @Provide
    Arbitrary<Integer> indexes() {
        return Arbitraries.integers().between(0, Entry.MAX_POSTINGS - 1);
    }

    private Arbitrary<List<Posting>> legs(Direction direction) {
        return Combinators.combine(Arbitraries.of(ACCOUNTS), amounts())
                .as((account, amount) -> new Posting(account, direction, amount))
                .list()
                .ofMinSize(1)
                .ofMaxSize(15);
    }

    private static long sum(List<Posting> postings) {
        return postings.stream()
                .mapToLong(posting -> posting.amount().minorUnits())
                .sum();
    }

    private static long total(Entry entry, Direction direction) {
        return sum(entry.postings().stream()
                .filter(posting -> posting.direction() == direction)
                .toList());
    }

    private static EntryId newId() {
        return new EntryId(UUID.randomUUID());
    }
}
