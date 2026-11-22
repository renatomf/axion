package dev.finaxis.core.billing;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.Money;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A charge as other modules see it (billing design, section 4.1).
 *
 * @param amountRefunded what refunds returned of the settling payment
 * @param transactionId the 25 letters or digits that identify the charge on the rails
 * @param brCode the text of the charge's QR code
 * @param paidAt when the rails accepted the settling payment; null until one did
 * @param environment the tenant's, so a client can tell test charges from live ones
 */
public record ChargeView(
        ChargeId id,
        ChargeStatus status,
        Money amount,
        Money amountRefunded,
        @Nullable String description,
        Map<String, String> metadata,
        String transactionId,
        String brCode,
        Instant expiresAt,
        @Nullable Instant paidAt,
        Instant createdAt,
        Environment environment) {

    public ChargeView {
        metadata = Map.copyOf(metadata);
    }
}
