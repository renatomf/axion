package dev.axion.core.audit;

import java.util.Optional;

/**
 * What one verification of a chain found.
 *
 * @param headSequence the sequence of the chain's head, as stored; 0 for a chain with no record
 * @param headHash the hash of the chain's head, as stored, in lowercase hex: what the nightly check
 *     logs outside the database, so that removing a chain's last records with its head shows too
 * @param breach the first break found; empty when the chain is intact
 */
public record Verification(ChainId chain, long headSequence, String headHash, Optional<ChainBreak> breach) {

    public boolean intact() {
        return breach.isEmpty();
    }
}
