package dev.finaxis.core.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a caller asks for when it posts an entry through {@code LedgerApi.post} (ledger design,
 * section 6).
 *
 * @param tenantId the tenant of the source fact; each posting belongs to its own account's tenant
 * @param source the fact that caused the entry; the key that makes posting idempotent
 * @param effectiveDate the business date in {@code America/Sao_Paulo}, from {@code
 *     BusinessCalendar}
 * @param correlationId the ID of the request that caused the entry, to trace it
 * @param reverses the entry this one undoes, if it is a reversal
 * @param postings the legs: two to 32, balanced per currency
 */
public record EntryRequest(
        UUID tenantId,
        EntrySource source,
        LocalDate effectiveDate,
        String correlationId,
        Optional<EntryId> reverses,
        List<PostingRequest> postings) {

    public EntryRequest {
        // A copy, so a caller changing its list afterwards cannot change the request.
        postings = List.copyOf(postings);
    }

    /** A request for an entry that reverses no other. */
    public EntryRequest(
            UUID tenantId,
            EntrySource source,
            LocalDate effectiveDate,
            String correlationId,
            List<PostingRequest> postings) {
        this(tenantId, source, effectiveDate, correlationId, Optional.empty(), postings);
    }
}
