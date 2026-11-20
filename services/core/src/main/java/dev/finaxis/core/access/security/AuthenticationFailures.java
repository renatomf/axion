package dev.finaxis.core.access.security;

import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.AuthenticationResult.Reason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Records why a request was refused a {@code 401} on {@code /v1} (tenancy design, section 4.4).
 * The caller gets the same answer whatever the reason; the reason goes to the {@value #FAILURES}
 * counter, tagged with it, and to a log line, with the key's ID when the credential names a key.
 *
 * <p>Neither ever holds the credential: a refused credential may be a working key with a typo, or
 * one sent to the wrong environment, and a log is no place for a secret.
 */
final class AuthenticationFailures {

    /** The counter's name; Prometheus shows it as {@code finaxis_authentication_failures_total}. */
    static final String FAILURES = "finaxis.authentication.failures";

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationFailures.class);

    /** Why a request was refused, as the {@code reason} tag shows it, in lower case. */
    enum Failure {
        /** Not an API key, or not a single {@code Bearer} credential. */
        MALFORMED,
        /** Shaped like a key, but no key has this secret. */
        UNKNOWN,
        /** The key was revoked. */
        REVOKED,
        /** The key was rolled and its grace period has ended. */
        EXPIRED;

        /** The failure for a reason {@code AccessApi.authenticate} gave. */
        static Failure of(Reason reason) {
            return switch (reason) {
                case MALFORMED -> MALFORMED;
                case UNKNOWN -> UNKNOWN;
                case REVOKED -> REVOKED;
                case EXPIRED -> EXPIRED;
            };
        }

        /** The value of the {@code reason} tag and log field, such as {@code revoked}. */
        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<Failure, Counter> counters = new EnumMap<>(Failure.class);

    /**
     * Registers one counter per failure at zero, so each series exists before its first failure and
     * a rate over it reads zero instead of nothing.
     */
    AuthenticationFailures(MeterRegistry meters) {
        for (Failure failure : Failure.values()) {
            counters.put(
                    failure,
                    Counter.builder(FAILURES)
                            .description("Requests refused a 401 on the public API, by reason")
                            .tag("reason", failure.tag())
                            .register(meters));
        }
    }

    /**
     * Counts and logs one refused request.
     *
     * @param failure why it was refused
     * @param key the key its credential names, when it names one
     */
    void record(Failure failure, Optional<ApiKeyId> key) {
        // The constructor registered a counter for every failure, so the lookup always finds one.
        Objects.requireNonNull(counters.get(failure)).increment();
        // The fluent builder adds each field as a key of its own in the JSON log line.
        LoggingEventBuilder line = LOG.atInfo().addKeyValue("reason", failure.tag());
        if (key.isPresent()) {
            line = line.addKeyValue("api_key_id", key.get().publicId().toString());
        }
        line.log("API key authentication failed");
    }
}
