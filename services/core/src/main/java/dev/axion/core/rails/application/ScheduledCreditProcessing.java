package dev.axion.core.rails.application;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs a pass of the {@link CreditProcessor} every 500 ms (billing design, section 7.3). Off when
 * {@code finaxis.rails.processor.enabled} is false, so tests run the passes themselves.
 */
@Component
@ConditionalOnBooleanProperty(name = "finaxis.rails.processor.enabled", matchIfMissing = true)
class ScheduledCreditProcessing {

    private final CreditProcessor processor;

    ScheduledCreditProcessing(CreditProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(initialDelay = 500, fixedDelay = 500, timeUnit = TimeUnit.MILLISECONDS)
    void processDue() {
        processor.processDue();
    }
}
