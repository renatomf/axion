package dev.finaxis.core.ledger;

import java.util.List;
import java.util.Optional;

/**
 * One page of a paginated read, such as {@code LedgerApi.history}.
 *
 * @param items the page's items, in the read's order
 * @param next the cursor of the following page, or empty if this page is the last
 * @param <T> the type of the items
 */
public record Page<T>(List<T> items, Optional<Cursor> next) {

    public Page {
        // A copy, so a caller changing its list afterwards cannot change the page.
        items = List.copyOf(items);
    }

    /** Whether there are more items after this page. */
    public boolean hasMore() {
        return next.isPresent();
    }
}
