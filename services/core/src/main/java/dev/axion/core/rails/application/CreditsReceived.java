package dev.axion.core.rails.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Counts the credit notifications the rails delivered, by what the core did with them (billing
 * design, section 12.4): recorded, recognized as a duplicate, or refused for a signature or a body
 * the core would not accept. A rising share of refusals is a broken or forged sender.
 */
@Component
public class CreditsReceived {

    public static final String METRIC = "axion.rails.credits.received";

    public enum Result {
        NEW,
        DUPLICATE,
        REJECTED
    }

    private final Map<Result, Counter> counters = new EnumMap<>(Result.class);

    // Registered at zero, so a rate is defined before the first credit of each result.
    CreditsReceived(MeterRegistry meters) {
        for (Result result : Result.values()) {
            counters.put(
                    result,
                    Counter.builder(METRIC)
                            .description("Credit notifications the rails delivered, by what the core did with them")
                            .tag("result", result.name().toLowerCase(Locale.ROOT))
                            .register(meters));
        }
    }

    public void count(Result result) {
        Objects.requireNonNull(counters.get(result)).increment();
    }
}
