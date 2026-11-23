package dev.axion.core.access.security;

import dev.axion.core.access.security.RateLimits.Quota;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes a request's quotas as the {@code RateLimit-Policy} and {@code RateLimit} fields of the
 * IETF draft <em>RateLimit header fields for HTTP</em>, one list member per bucket, so a client
 * sees how much budget is left before it is refused (tenancy design, section 6):
 *
 * <pre>
 * RateLimit-Policy: "key";q=100;w=2, "tenant";q=200;w=2
 * RateLimit: "key";r=99;t=1, "tenant";r=199;t=1
 * </pre>
 *
 * <p>{@code q} is the burst and {@code w} the seconds a full burst takes to refill; {@code r} is the
 * requests left and {@code t} the seconds until the bucket is full again.
 */
final class RateLimitHeaders {

    static final String POLICY = "RateLimit-Policy";
    static final String STATE = "RateLimit";

    private RateLimitHeaders() {}

    static void write(HttpServletResponse response, List<Quota> quotas) {
        response.setHeader(
                POLICY,
                list(
                        quotas,
                        quota -> "q=" + quota.limit().burst() + ";w="
                                + quota.limit().windowSeconds()));
        response.setHeader(STATE, list(quotas, quota -> "r=" + quota.remaining() + ";t=" + quota.resetSeconds()));
    }

    private static String list(List<Quota> quotas, Function<Quota, String> parameters) {
        return quotas.stream()
                .map(quota -> "\"" + quota.policy() + "\";" + parameters.apply(quota))
                .collect(Collectors.joining(", "));
    }
}
