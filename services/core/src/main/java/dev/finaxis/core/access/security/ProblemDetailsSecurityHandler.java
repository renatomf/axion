package dev.finaxis.core.access.security;

import dev.finaxis.core.access.web.AccessProblems;
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
 * Answers Spring Security's rejections with the same Problem Details as every other error, by
 * handing them to Spring MVC's error handling. Details stay generic, so a rejection reveals nothing
 * about which credentials or paths exist.
 */
class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    ProblemDetailsSecurityHandler(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) {
        reject(request, response, HttpStatus.UNAUTHORIZED, "Authentication is required.");
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) {
        reject(request, response, HttpStatus.FORBIDDEN, "Access is denied.");
    }

    // The caller proved it holds a working key, so it may learn why it is refused.
    void tenantInactive(HttpServletRequest request, HttpServletResponse response) {
        resolver.resolveException(request, response, null, AccessProblems.tenantInactive());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        resolver.resolveException(request, response, null, new ErrorResponseException(status, problem, null));
    }
}
