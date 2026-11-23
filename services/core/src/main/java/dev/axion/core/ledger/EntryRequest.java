package dev.axion.core.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An entry to post (ledger design, section 6).
 *
 * @param tenantId the source fact's tenant; each posting belongs to its own account's tenant
 * @param effectiveDate the business date in {@code America/Sao_Paulo}
 * @param postings two to 32, balanced per currency
 */
public record EntryRequest(
        UUID tenantId,
        EntrySource source,
        LocalDate effectiveDate,
        String correlationId,
        Optional<EntryId> reverses,
        List<PostingRequest> postings) {

    public EntryRequest {
        postings = List.copyOf(postings);
    }

    public EntryRequest(
            UUID tenantId,
            EntrySource source,
            LocalDate effectiveDate,
            String correlationId,
            List<PostingRequest> postings) {
        this(tenantId, source, effectiveDate, correlationId, Optional.empty(), postings);
    }
}
