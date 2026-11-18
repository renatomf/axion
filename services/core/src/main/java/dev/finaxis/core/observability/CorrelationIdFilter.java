package dev.finaxis.core.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every HTTP request a correlation ID and adds it to every log line written while the request
 * is handled.
 *
 * <p>The ID comes from the {@value #HEADER} request header when the caller sends a valid one, so a
 * client can follow its own request through our logs; otherwise a new one is generated. It is
 * returned in the same response header and stored in the logging MDC under {@value #MDC_KEY}, which
 * the structured log format writes as a JSON field.
 *
 * <p>A client-supplied ID is only a label for logs, never trusted for anything else. It is accepted
 * only if it matches {@code [A-Za-z0-9._-]{1,64}}, so a caller cannot inject line breaks, markup or
 * very long values into the logs.
 *
 * <p>{@code @Component} registers the filter as a bean; Spring Boot then adds every {@code Filter}
 * bean to the embedded server. {@code @Order(HIGHEST_PRECEDENCE)} runs it before any other filter,
 * so their logs carry the ID too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Server threads are pooled: without this, the next request on the
            // same thread would log with this request's ID.
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(@Nullable String candidate) {
        if (candidate != null && VALID_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
