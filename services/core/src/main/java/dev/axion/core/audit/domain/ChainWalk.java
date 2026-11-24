package dev.axion.core.audit.domain;

import dev.axion.core.audit.ChainBreak;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One walk of a chain, fed its records in sequence order (events and webhooks design, section 9.3).
 * It stops at the first break: what follows a break cannot be trusted to describe the chain.
 */
public final class ChainWalk {

    private long expected = 1;
    private ChainHash last = ChainHash.ZERO;
    private @Nullable ChainBreak found;

    /** Checks the next record: that it holds the next sequence, its own hash, and links to the last. */
    public void visit(ChainedRecord stored) {
        if (found != null) {
            return;
        }
        long sequence = stored.record().sequence();
        if (sequence != expected) {
            found = new ChainBreak(expected, ChainBreak.Kind.MISSING);
        } else if (!stored.record().hash(stored.previous()).equals(stored.hash())) {
            found = new ChainBreak(sequence, ChainBreak.Kind.ALTERED);
        } else if (!stored.previous().equals(last)) {
            found = new ChainBreak(sequence, ChainBreak.Kind.UNLINKED);
        } else {
            last = stored.hash();
            expected++;
        }
    }

    public boolean broken() {
        return found != null;
    }

    /**
     * Ends the walk with the chain's head, which must be the last record visited.
     *
     * @return the first break found; empty when the chain is intact
     */
    public Optional<ChainBreak> finish(long headSequence, ChainHash headHash) {
        if (found == null && (headSequence != expected - 1 || !headHash.equals(last))) {
            found = new ChainBreak(headSequence, ChainBreak.Kind.HEAD_MISMATCH);
        }
        return Optional.ofNullable(found);
    }
}
