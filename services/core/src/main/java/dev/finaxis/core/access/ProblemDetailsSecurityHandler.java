package dev.finaxis.core.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers requests that Spring Security rejects with the same RFC 9457 Problem Details as every
 * other error: {@code 401} when the caller is not authenticated, {@code 403} when an authenticated
 * caller may not do what it asked.
 *
 * <p>Spring Security rejects a request in a servlet filter, before Spring MVC runs, so the
 * {@code @RestControllerAdvice} in the {@code web} module never sees the rejection. Instead of
 * writing JSON here, the rejection is handed to Spring MVC's {@link HandlerExceptionResolver} as an
 * {@link ErrorResponseException}: the advice then builds the response, including its {@code code}.
 *
 * <p>The detail is deliberately generic. It never says why the request was rejected, so it gives an
 * attacker no hint about which credentials or paths exist.
 */
class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    ProblemDetailsSecurityHandler(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    /** Called when an unauthenticated caller asks for anything that is not public. */
    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) {
        reject(request, response, HttpStatus.UNAUTHORIZED, "Authentication is required.");
    }

    /** Called when an authenticated caller is not allowed to do what it asked. */
    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) {
        reject(request, response, HttpStatus.FORBIDDEN, "Access is denied.");
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        resolver.resolveException(request, response, null, new ErrorResponseException(status, problem, null));
    }
}
