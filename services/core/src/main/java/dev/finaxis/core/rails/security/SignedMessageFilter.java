package dev.finaxis.core.rails.security;

import dev.finaxis.core.rails.security.SignatureVerifier.Verification;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
// Not a bean: Spring Boot would also register it with the servlet container, for every path.
final class SignedMessageFilter extends OncePerRequestFilter {

    static final int MAX_BODY = 16 * 1024;

    private static final String TOO_LARGE = "A request body is at most " + MAX_BODY / 1024 + " KiB.";

    private static final Logger LOG = LoggerFactory.getLogger(SignedMessageFilter.class);

    private final SignatureVerifier verifier;
    private final HandlerExceptionResolver resolver;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    SignedMessageFilter(SignatureVerifier verifier, HandlerExceptionResolver resolver) {
        this.verifier = verifier;
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

    private void reject(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        resolver.resolveException(request, response, null, new ErrorResponseException(status, problem, null));
    }
}
