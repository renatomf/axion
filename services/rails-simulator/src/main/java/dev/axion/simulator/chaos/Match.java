package dev.axion.simulator.chaos;

import org.jspecify.annotations.Nullable;

/**
 * Which deliveries a rule applies to: those of one payment, those of one BR Code's transaction ID, both
 * when both are given, and every delivery when neither is.
 */
public record Match(@Nullable String endToEndId, @Nullable String transactionId) {

    static final Match ANY = new Match(null, null);

    boolean matches(String endToEndId, String transactionId) {
        return (this.endToEndId == null || this.endToEndId.equals(endToEndId))
                && (this.transactionId == null || this.transactionId.equals(transactionId));
    }
}
