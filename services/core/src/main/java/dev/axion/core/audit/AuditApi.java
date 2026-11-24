package dev.axion.core.audit;

/** The audit trail's check (events and webhooks design, section 9.3). */
public interface AuditApi {

    /**
     * Walks the chain in sequence order, as one snapshot: recomputes every record's hash, checks each
     * links to the one before it and that no sequence is missing, and that the head is the last
     * record. A chain with no record is intact.
     */
    Verification verify(ChainId chain);
}
