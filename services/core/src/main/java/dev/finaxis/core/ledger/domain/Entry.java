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
 * One money movement whose debits equal its credits in each currency (invariant I1, ledger design,
 * section 4). Never changed: a mistake is undone by a {@linkplain #reversal reversal}. The database
 * checks the balance again at commit.
 */
public record Entry(EntryId id, List<Posting> postings, Optional<EntryId> reverses) {

    /** Enough for the widest split; a bound keeps one entry from holding many balance locks. */
    public static final int MAX_POSTINGS = 32;

    public Entry {
        // A copy, so a caller cannot unbalance the entry afterwards.
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

    public Entry(EntryId id, List<Posting> postings) {
        this(id, postings, Optional.empty());
    }

    /** The same postings in the opposite direction, linked to this entry. */
    public Entry reversal(EntryId id) {
        return new Entry(id, postings.stream().map(Posting::reversed).toList(), Optional.of(this.id));
    }

    /** One delta per account, so each balance is updated once per entry. */
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
