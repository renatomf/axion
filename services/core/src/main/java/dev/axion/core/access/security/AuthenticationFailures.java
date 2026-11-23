package dev.axion.core.access.security;

import dev.axion.core.access.AuthenticationResult;
import dev.axion.core.shared.ApiKeyId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Records why a {@code /v1} request got a {@code 401}, which the response hides (tenancy design,
 * section 4.4). The credential itself is never recorded.
 */
final class AuthenticationFailures {

    static final String FAILURES = "axion.authentication.failures";

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationFailures.class);

    enum Failure {
        MISSING,
        MALFORMED,
        UNKNOWN,
        REVOKED,
        EXPIRED;

        static Failure of(AuthenticationResult.Reason reason) {
            return switch (reason) {
                case MALFORMED -> MALFORMED;
                case UNKNOWN -> UNKNOWN;
                case REVOKED -> REVOKED;
                case EXPIRED -> EXPIRED;
            };
        }

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry meters;

    AuthenticationFailures(MeterRegistry meters) {
        this.meters = meters;
        // Registered at zero, so an alert fires on the first failure.
        for (Failure failure : Failure.values()) {
            counter(failure);
        }
    }

    void record(Failure failure, Optional<ApiKeyId> key) {
        counter(failure).increment();
        LoggingEventBuilder line = LOG.atInfo().addKeyValue("reason", failure.tag());
        if (key.isPresent()) {
            line = line.addKeyValue("api_key_id", key.get().publicId().toString());
        }
        line.log("API key authentication failed");
    }

    private Counter counter(Failure failure) {
        return meters.counter(FAILURES, "reason", failure.tag());
    }
}
