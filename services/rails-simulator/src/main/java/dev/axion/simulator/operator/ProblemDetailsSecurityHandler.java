package dev.axion.simulator.operator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers Spring Security's rejections with the same Problem Details as every other error, by handing
 * them to Spring MVC's error handling. Details stay generic, so a rejection reveals nothing about the
 * token.
 */
class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    ProblemDetailsSecurityHandler(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) {
        var rejection = rejection(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        rejection.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        resolver.resolveException(request, response, null, rejection);
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) {
        resolver.resolveException(request, response, null, rejection(HttpStatus.FORBIDDEN, "Access is denied."));
    }

    private static ErrorResponseException rejection(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        return new ErrorResponseException(status, problem, null);
    }
}
