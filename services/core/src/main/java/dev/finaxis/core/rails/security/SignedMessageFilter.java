package dev.finaxis.core.rails.security;

import dev.finaxis.core.rails.application.CreditsReceived;
import dev.finaxis.core.rails.security.SignatureVerifier.Verification;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authenticates a {@code /rails} request by the rails' signature (billing design, section 7.1): it
 * reads the body in full, at most {@value #MAX_BODY} bytes, verifies it, and hands the verified
 * bytes on as a {@link SignedMessage}. A refusal is a {@code 401} with no detail, whatever failed.
 * A key past its budget gets {@code 429 rate_limited} with {@code Retry-After}, which the rails honor.
 */
// Not a bean: Spring Boot would also register it with the servlet container, for every path.
final class SignedMessageFilter extends OncePerRequestFilter {

    static final int MAX_BODY = 16 * 1024;

    private static final String TOO_LARGE = "A request body is at most " + MAX_BODY / 1024 + " KiB.";

    private static final Logger LOG = LoggerFactory.getLogger(SignedMessageFilter.class);

    private final SignatureVerifier verifier;
    private final SigningKeyRateLimits limits;
    private final CreditsReceived received;
    private final HandlerExceptionResolver resolver;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    SignedMessageFilter(
            SignatureVerifier verifier,
            SigningKeyRateLimits limits,
            CreditsReceived received,
            HandlerExceptionResolver resolver) {
        this.verifier = verifier;
        this.limits = limits;
        this.received = received;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            reject(request, response, HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            reject(request, response, HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE);
            return;
        }
        switch (verifier.verify(request, body)) {
            case Verification.Verified(var keyId) -> {
                Optional<Duration> retryAfter = limits.tryAcquire(keyId);
                if (retryAfter.isPresent()) {
                    rateLimited(request, response, retryAfter.get());
                    return;
                }
                SecurityContext context = contexts.createEmptyContext();
                context.setAuthentication(new PreAuthenticatedAuthenticationToken(keyId, null, List.of()));
                contexts.setContext(context);
                request.setAttribute(SignedMessage.ATTRIBUTE, new SignedMessage(keyId, body));
                chain.doFilter(request, response);
            }
            case Verification.Refused(var reason) -> {
                // Only the reason: the headers and the body are the caller's, and could forge log lines.
                LOG.warn("Refused a rails request: {}", reason);
                reject(request, response, HttpStatus.UNAUTHORIZED, "Authentication is required.");
            }
        }
    }

    private void rateLimited(HttpServletRequest request, HttpServletResponse response, Duration retryAfter) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; retry later.");
        problem.setProperty("code", "rate_limited");
        var rejection = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, problem, null);
        // Whole seconds, rounded up, and never 0: a retry at once would be refused again.
        long seconds = Math.max(1, retryAfter.plusSeconds(1).minusNanos(1).toSeconds());
        rejection.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        resolver.resolveException(request, response, null, rejection);
    }

    // Refused for good: the rails keep the message for an operator, so it counts as rejected.
    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail) {
        received.count(CreditsReceived.Result.REJECTED);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        resolver.resolveException(request, response, null, new ErrorResponseException(status, problem, null));
    }
}
