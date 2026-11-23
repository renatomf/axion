package dev.axion.core.ledger;

import dev.axion.core.shared.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One posting of an account's history, with its entry's source.
 *
 * @param createdAt from the database clock
 */
public record PostingView(
        PostingId id,
        EntryId entry,
        EntrySource source,
        Direction direction,
        Money amount,
        LocalDate effectiveDate,
        Instant createdAt) {}
