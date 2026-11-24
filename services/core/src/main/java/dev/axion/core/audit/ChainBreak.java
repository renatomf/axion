package dev.axion.core.audit;

/**
 * Where a chain stops being intact: the first sequence at which what is stored is not what was
 * appended (events and webhooks design, section 9.3).
 */
public record ChainBreak(long sequence, Kind kind) {

    public enum Kind {
        /** The record's hash is not the hash of its fields: the record was changed. */
        ALTERED,
        /** The record does not link to the hash of the record before it: one of them was replaced. */
        UNLINKED,
        /** No record holds the sequence: a record was removed, or records were reordered. */
        MISSING,
        /** The head is not the chain's last record: records were removed from its end, or added past it. */
        HEAD_MISMATCH
    }
}
