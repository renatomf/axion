package dev.finaxis.core.idempotency.web;

import dev.finaxis.core.idempotency.IdempotencyKey;
import dev.finaxis.core.web.HandlerStep;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

/**
 * Requires an {@code Idempotency-Key} on every {@code POST} under {@code /v1} (idempotency design,
 * section 3.1). It runs after the permission check, so a caller learns nothing here that it could
 * not learn from a request it may make.
 */
@Component
final class IdempotencyStep implements HandlerStep {

    static final String HEADER = "Idempotency-Key";

    private static final String PUBLIC_API = "/v1/";

    @Override
    public int getOrder() {
        return IDEMPOTENCY;
    }

    @Override
    public @Nullable ModelAndView invoke(
            HttpServletRequest request, HttpServletResponse response, HandlerMethod handler, Next next)
            throws Exception {
        if (!isPublicApiWrite(request)) {
            return next.invoke(request, response);
        }
        key(request);
        return next.invoke(request, response);
    }

    static boolean isPublicApiWrite(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && path(request).startsWith(PUBLIC_API);
    }

    /** The path as received, without the context path. */
    static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static IdempotencyKey key(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HEADER));
        if (values.isEmpty()) {
            throw IdempotencyProblems.keyRequired();
        }
        // Two keys name no single operation.
        if (values.size() > 1) {
            throw IdempotencyProblems.invalidKey();
        }
        String value = values.getFirst();
        try {
            return new IdempotencyKey(value);
        } catch (IllegalArgumentException invalid) {
            throw IdempotencyProblems.invalidKey();
        }
    }
}
