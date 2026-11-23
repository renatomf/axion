package dev.axion.simulator.operator;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error of the operator API is an RFC 9457 Problem Details document: Spring MVC's own errors
 * through the base class, and an unexpected exception as a {@code 500} with a generic detail, the
 * exception only logged.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        // Nothing from the request is logged: a caller-chosen path could forge log lines.
        LOG.error("Unexpected error while handling a request", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "An unexpected error occurred.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), status, request);
    }
}
