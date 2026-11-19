package dev.finaxis.core.ledger;

import dev.finaxis.core.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One posting in an account's history, as {@code LedgerApi.history} returns it (ledger design,
 * section 6), with what its entry says about why it happened.
 *
 * @param id the posting's ID, also the position of the next page's cursor
 * @param entry the entry the posting belongs to
 * @param source the fact that caused the entry
 * @param direction debit or credit
 * @param amount the amount, positive and in the account's currency
 * @param effectiveDate the entry's business date in {@code America/Sao_Paulo}
 * @param createdAt when the posting was written, from the database clock
 */
public record PostingView(
        PostingId id,
        EntryId entry,
        EntrySource source,
        Direction direction,
        Money amount,
        LocalDate effectiveDate,
        Instant createdAt) {}
