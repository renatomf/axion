package dev.axion.core.billing;

import dev.axion.core.billing.domain.Charge;
import dev.axion.core.shared.Money;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A charge to create, within the limits of the billing design, section 4.1; construction throws
 * {@code IllegalArgumentException} for a field outside them. The public API checks the same limits
 * first and answers {@code 422} instead.
 *
 * @param description for the tenant's records; never in the BR Code
 * @param metadata the tenant's own keys and values, stored and returned, never read by Finaxis
 */
public record ChargeRequest(
        Money amount, @Nullable String description, Duration expiresIn, Map<String, String> metadata) {

    public static final int MAX_DESCRIPTION_LENGTH = 140;

    public static final Duration MIN_EXPIRES_IN = Charge.MIN_LIFETIME;

    public static final Duration MAX_EXPIRES_IN = Duration.ofDays(7);

    /** The default of the Central Bank's Pix API. */
    public static final Duration DEFAULT_EXPIRES_IN = Duration.ofDays(1);

    public static final int MAX_METADATA_KEYS = 20;

    public static final int MAX_METADATA_KEY_LENGTH = 40;

    public static final int MAX_METADATA_VALUE_LENGTH = 500;

    // Lengths count code points, as PostgreSQL's char_length does.
    public ChargeRequest {
        metadata = Map.copyOf(metadata);
        if (!amount.currency().equals(Charge.CURRENCY)) {
            throw new IllegalArgumentException("A charge is in " + Charge.CURRENCY + ", not " + amount.currency());
        }
        if (!amount.isPositive() || amount.minorUnits() > Charge.MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("A charge's amount is 1 to " + Charge.MAX_AMOUNT_MINOR
                    + " minor units, not " + amount.minorUnits());
        }
        if (description != null && !isBetween(description, 1, MAX_DESCRIPTION_LENGTH)) {
            throw new IllegalArgumentException(
                    "A description is 1 to " + MAX_DESCRIPTION_LENGTH + " characters, not " + length(description));
        }
        if (expiresIn.compareTo(MIN_EXPIRES_IN) < 0 || expiresIn.compareTo(MAX_EXPIRES_IN) > 0) {
            throw new IllegalArgumentException("A charge expires " + MIN_EXPIRES_IN + " to " + MAX_EXPIRES_IN
                    + " after creation, not " + expiresIn);
        }
        requireMetadataWithinLimits(metadata);
    }

    /** A charge of the amount, with no description or metadata, expiring after {@link #DEFAULT_EXPIRES_IN}. */
    public static ChargeRequest of(Money amount) {
        return new ChargeRequest(amount, null, DEFAULT_EXPIRES_IN, Map.of());
    }

    // The keys and values are not echoed: the tenant's metadata is never logged.
    private static void requireMetadataWithinLimits(Map<String, String> metadata) {
        if (metadata.size() > MAX_METADATA_KEYS) {
            throw new IllegalArgumentException(
                    "Metadata holds at most " + MAX_METADATA_KEYS + " keys, not " + metadata.size());
        }
        metadata.forEach((key, value) -> {
            if (!isBetween(key, 1, MAX_METADATA_KEY_LENGTH)) {
                throw new IllegalArgumentException(
                        "A metadata key is 1 to " + MAX_METADATA_KEY_LENGTH + " characters, not " + length(key));
            }
            if (length(value) > MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException("A metadata value is at most " + MAX_METADATA_VALUE_LENGTH
                        + " characters, not " + length(value));
            }
        });
    }

    private static boolean isBetween(String text, int min, int max) {
        int length = length(text);
        return length >= min && length <= max;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
