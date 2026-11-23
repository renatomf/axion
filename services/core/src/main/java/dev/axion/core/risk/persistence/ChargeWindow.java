package dev.axion.core.risk.persistence;

import dev.axion.core.risk.RiskRule;
import java.time.Instant;

/**
 * The window of a volume rule that a charge falls in, with the most it may hold.
 *
 * @param maxTotal the largest total of its charges, in centavos
 * @param maxCount the most charges it may hold
 */
public record ChargeWindow(RiskRule rule, Instant start, long maxTotal, int maxCount) {}
