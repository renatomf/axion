package dev.axion.core.ledger;

import java.util.Optional;

/**
 * Where a page of {@link LedgerApi#history} starts. A position, not a page number: a deep page
 * costs the same, and new postings do not shift the pages.
 */
public record Cursor(Optional<PostingId> after) {

    public static Cursor first() {
        return new Cursor(Optional.empty());
    }

    public static Cursor after(PostingId last) {
        return new Cursor(Optional.of(last));
    }
}
