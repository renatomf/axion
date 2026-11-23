package dev.axion.core.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * The body a repeat of a {@code /v1} write is answered with, when it must not be the one first sent
 * (idempotency design, section 15): a response that shows a secret once is stored, and replayed,
 * without it. A controller sets it before it returns; the idempotency step stores it, serialized as
 * the response is, in place of the bytes written.
 */
public final class ReplayBody {

    private static final String ATTRIBUTE = ReplayBody.class.getName();

    private ReplayBody() {}

    public static void set(HttpServletRequest request, Object body) {
        request.setAttribute(ATTRIBUTE, body);
    }

    public static Optional<Object> of(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(ATTRIBUTE));
    }
}
