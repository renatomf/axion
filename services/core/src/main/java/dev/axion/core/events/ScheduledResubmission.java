package dev.axion.core.events;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link FailedPublicationResubmission} every minute. Off when {@code
 * axion.events.resubmission.enabled} is false, so tests run it themselves.
 */
@Component
@ConditionalOnBooleanProperty(name = "axion.events.resubmission.enabled", matchIfMissing = true)
class ScheduledResubmission {

    private final FailedPublicationResubmission resubmission;

    ScheduledResubmission(FailedPublicationResubmission resubmission) {
        this.resubmission = resubmission;
    }

    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    void resubmitDue() {
        resubmission.resubmitDue();
    }
}
