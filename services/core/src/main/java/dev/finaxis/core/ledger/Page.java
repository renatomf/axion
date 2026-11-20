package dev.finaxis.core.ledger;

import java.util.List;
import java.util.Optional;

/** One page of a paginated read; {@code next} is empty on the last page. */
public record Page<T>(List<T> items, Optional<Cursor> next) {

    public Page {
        items = List.copyOf(items);
    }

    public boolean hasMore() {
        return next.isPresent();
    }
}
