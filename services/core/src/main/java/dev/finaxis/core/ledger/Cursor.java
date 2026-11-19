package dev.finaxis.core.ledger;

import java.util.Optional;

/**
 * Where a page of {@code LedgerApi.history} starts (ledger design, section 6): at the newest
 * posting, or right after the last posting of the previous page.
 *
 * <p>The cursor is a position, not a page number: a page starts after a posting ID, so reading it
 * costs the same however deep in the history it is, and postings written meanwhile do not shift
 * the pages.
 *
 * @param after the last posting of the previous page, or empty for the first page
 */
public record Cursor(Optional<PostingId> after) {

    /** The first page: the newest postings. */
    public static Cursor first() {
        return new Cursor(Optional.empty());
    }

    /** The page of postings older than {@code last}. */
    public static Cursor after(PostingId last) {
        return new Cursor(Optional.of(last));
    }
}
