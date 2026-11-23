package dev.axion.core.billing.application;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ChargeExpiry} every 30 seconds (billing design, section 9). Off when {@code
 * finaxis.billing.expiry.enabled} is false, so tests run the job themselves.
 */
@Component
@ConditionalOnBooleanProperty(name = "finaxis.billing.expiry.enabled", matchIfMissing = true)
class ScheduledChargeExpiry {

    private final ChargeExpiry expiry;

    ScheduledChargeExpiry(ChargeExpiry expiry) {
        this.expiry = expiry;
    }

    @Scheduled(initialDelay = 30, fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    void expireDue() {
        expiry.expireDue();
    }
}
