package dev.axion.core.idempotency.web;

import dev.axion.core.idempotency.CapturedResponse;
import dev.axion.core.idempotency.Fingerprint;
import dev.axion.core.idempotency.IdempotencyApi;
import dev.axion.core.idempotency.IdempotencyKey;
import dev.axion.core.idempotency.IdempotentRequest;
import dev.axion.core.idempotency.IdempotentResult;
import dev.axion.core.observability.CorrelationIdFilter;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.web.HandlerStep;
import dev.axion.core.web.ReplayBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs every {@code POST} under {@code /v1} once per {@code Idempotency-Key} of the caller's tenant
 * (idempotency design, sections 3 and 4.3). The key and the body, of at most 64 KiB, are checked
 * before the controller runs; the controller then runs, from argument resolution to the written
 * response, inside {@link IdempotencyApi#execute}, so its effect and its captured response commit
 * together. A repeat gets the stored response, with {@value #REPLAYED_HEADER}.
 *
 * <p>It runs after the permission check, so a replay is given only to a caller who could make the
 * request now, and inside the tenant binding, so the key's row is under Row-Level Security.
 *
 * <p>An operation whose response shows a secret once sets a {@link ReplayBody} without it, which is
 * stored instead of the bytes it wrote, so no secret is kept in plaintext with the key.
 */
@Component
final class IdempotencyStep implements HandlerStep {

    static final String HEADER = "Idempotency-Key";

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    static final int MAX_BODY = 64 * 1024;

    private static final String PUBLIC_API = "/v1/";

    private final IdempotencyApi idempotency;
    private final HandlerExceptionResolver exceptionResolver;
    private final JsonMapper json;

    // The JsonMapper MVC writes responses with, so a replay body is serialized as a response is.
    IdempotencyStep(
            IdempotencyApi idempotency,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            JsonMapper json) {
        this.idempotency = idempotency;
        this.exceptionResolver = exceptionResolver;
        this.json = json;
    }

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
        IdempotencyKey key = key(request);
        CachedBodyRequest cached = CachedBodyRequest.read(request, MAX_BODY);
        IdempotentRequest idempotent =
                new IdempotentRequest(tenant(), key, Fingerprint.of(path(request), cached.body()), correlationId());
        ContentCachingResponseWrapper capturing = new ContentCachingResponseWrapper(response);
        Operation operation = new Operation(cached, capturing, handler, next);
        Map<String, List<String>> headersBefore = headers(response);

        IdempotentResult result;
        try {
            result = idempotency.execute(idempotent, operation);
        } catch (RuntimeException failure) {
            // The operation rolled back, even when its commit failed after it answered, so the
            // headers it wrote, such as a Location, describe nothing and must not reach the client.
            discardOperationHeaders(response, headersBefore);
            throw failure instanceof OperationFailure checked ? checked.cause : failure;
        }
        return switch (result) {
            case IdempotentResult.Executed executed -> {
                capturing.copyBodyToResponse();
                yield operation.modelAndView;
            }
            case IdempotentResult.Replayed replayed -> {
                replay(replayed.response().response(), response);
                yield null;
            }
            case IdempotentResult.InProgress inProgress -> throw IdempotencyProblems.requestInProgress();
            case IdempotentResult.KeyReused reused -> throw IdempotencyProblems.keyReused();
        };
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
        try {
            return new IdempotencyKey(values.getFirst());
        } catch (IllegalArgumentException invalid) {
            throw IdempotencyProblems.invalidKey();
        }
    }

    private static TenantId tenant() {
        return TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("A /v1 write must run with its caller's tenant bound"))
                .tenant();
    }

    private static String correlationId() {
        return Objects.requireNonNull(
                MDC.get(CorrelationIdFilter.MDC_KEY), "Every request has a correlation ID before it reaches MVC");
    }

    private static Map<String, List<String>> headers(HttpServletResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            headers.putIfAbsent(name, List.copyOf(response.getHeaders(name)));
        }
        return headers;
    }

    // Only the body is held back until the outcome is known; headers reach the response as they are
    // written. The body is not committed yet, so a reset can still take them back.
    private static void discardOperationHeaders(HttpServletResponse response, Map<String, List<String>> before) {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        before.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }

    // The headers that describe this request, such as its correlation ID and rate limits, are already
    // on the response and stay its own (section 3.4).
    private static void replay(CapturedResponse stored, HttpServletResponse response) throws IOException {
        response.setStatus(stored.status());
        response.setContentType(stored.contentType());
        stored.location().ifPresent(location -> response.setHeader(HttpHeaders.LOCATION, location));
        response.setHeader(REPLAYED_HEADER, "true");
        byte[] body = stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /**
     * The controller, run inside the key's transaction. A problem the controller throws with a 4xx
     * status is its answer, as a returned refusal is: it is written here, through the usual
     * exception handling, so that it is stored and replayed. Anything else it throws rolls back.
     */
    private final class Operation implements Supplier<CapturedResponse> {

        private final CachedBodyRequest request;
        private final ContentCachingResponseWrapper response;
        private final HandlerMethod handler;
        private final Next next;
        private @Nullable ModelAndView modelAndView;

        Operation(CachedBodyRequest request, ContentCachingResponseWrapper response, HandlerMethod handler, Next next) {
            this.request = request;
            this.response = response;
            this.handler = handler;
            this.next = next;
        }

        @Override
        public CapturedResponse get() {
            try {
                modelAndView = next.invoke(request, response);
            } catch (ErrorResponseException refusal) {
                if (!refusal.getStatusCode().is4xxClientError()) {
                    throw refusal;
                }
                if (exceptionResolver.resolveException(request, response, handler, refusal) == null) {
                    throw refusal;
                }
                modelAndView = null;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new OperationFailure(failure);
            }
            @Nullable String contentType = response.getContentType();
            if (contentType == null) {
                throw new IllegalStateException("A /v1 write must answer with a body and its content type");
            }
            byte[] stored =
                    ReplayBody.of(request).map(json::writeValueAsBytes).orElseGet(response::getContentAsByteArray);
            return new CapturedResponse(
                    response.getStatus(), contentType, response.getHeader(HttpHeaders.LOCATION), stored);
        }
    }

    /** Carries a checked exception of the controller out of the transaction, which it rolled back. */
    private static final class OperationFailure extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Exception cause;

        OperationFailure(Exception cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
