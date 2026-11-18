package dev.finaxis.core.ledger.domain;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.Direction;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.shared.Money;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One money movement: two or more postings whose debits equal their credits in each currency
 * (ledger design, sections 3.1 and 4). Entries are never changed or deleted; a mistake is corrected
 * by a {@linkplain #reversal reversal}, a new entry that undoes it.
 *
 * <p>The constructor enforces invariant I1, so an unbalanced entry cannot exist in memory; the
 * database checks it again at commit (PR 1.09). A broken entry is a programming error in the caller,
 * so it throws.
 *
 * <p>The source of the entry, its tenant, business date and correlation ID join it with the API that
 * posts it (PR 1.10).
 *
 * @param id the entry's primary key
 * @param postings the legs, in the order given; between two and {@value #MAX_POSTINGS}
 * @param reverses the entry this one undoes, if it is a reversal
 */
public record Entry(EntryId id, List<Posting> postings, Optional<EntryId> reverses) {

    /** Enough for the widest split; a bound keeps one entry from holding many balance locks. */
    public static final int MAX_POSTINGS = 32;

    public Entry {
        // A copy, so a caller changing its list afterwards cannot unbalance the entry.
        postings = List.copyOf(postings);
        if (postings.size() < 2 || postings.size() > MAX_POSTINGS) {
            throw new IllegalArgumentException(
                    "An entry has 2 to " + MAX_POSTINGS + " postings, not " + postings.size());
        }
        for (Map.Entry<Currency, Money> net : debitsMinusCredits(postings).entrySet()) {
            if (!net.getValue().isZero()) {
                throw new IllegalArgumentException(
                        "Entry is unbalanced in " + net.getKey() + ": debits minus credits is " + net.getValue());
            }
        }
        if (reverses.isPresent() && reverses.get().equals(id)) {
            throw new IllegalArgumentException("An entry cannot reverse itself");
        }
    }

    /** An entry that reverses no other. */
    public Entry(EntryId id, List<Posting> postings) {
        this(id, postings, Optional.empty());
    }

    /**
     * A new entry that undoes this one: the same postings in the opposite direction, linked to this
     * entry. Together the two change no balance.
     *
     * @param id the new entry's primary key
     */
    public Entry reversal(EntryId id) {
        return new Entry(id, postings.stream().map(Posting::reversed).toList(), Optional.of(this.id));
    }

    /**
     * How much the entry changes each account's balance, signed as in {@link
     * Posting#balanceEffect()}. Postings to the same account are added together, so each account
     * appears once: its balance is updated once per entry.
     */
    public Map<AccountId, Money> balanceChanges() {
        Map<AccountId, Money> changes = new LinkedHashMap<>();
        for (Posting posting : postings) {
            changes.merge(posting.account().id(), posting.balanceEffect(), Money::plus);
        }
        return changes;
    }

    private static Map<Currency, Money> debitsMinusCredits(List<Posting> postings) {
        Map<Currency, Money> net = new LinkedHashMap<>();
        for (Posting posting : postings) {
            Money signed = posting.direction() == Direction.DEBIT
                    ? posting.amount()
                    : posting.amount().negate();
            net.merge(signed.currency(), signed, Money::plus);
        }
        return net;
    }
}
