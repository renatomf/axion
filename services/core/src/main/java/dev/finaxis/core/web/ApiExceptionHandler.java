package dev.finaxis.core.web;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * The one place that decides what an error looks like: RFC 9457 Problem Details, each with a stable
 * snake_case {@value #CODE} clients can branch on. An unknown exception is a {@code 500} with a
 * generic detail; the exception is only logged, with the correlation ID.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";

    static final String VALIDATION_FAILED = "validation_failed";

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Pattern CAMEL_HUMP = Pattern.compile("([a-z0-9])([A-Z])");

    private static final Pattern PATH_SEGMENT = Pattern.compile("\\[[^\\]]*]|[^\\[]+");

    @ExceptionHandler(Exception.class)
    public @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        // Nothing from the request is logged: a caller-chosen path could forge log lines.
        LOG.error("Unexpected error while handling a request", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "An unexpected error occurred.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), status, request);
    }

    // 422, not Spring's 400: the body was read, its content is unacceptable (RFC 9110, section 15.5.21).
    // Sorted, and the rejected value is never echoed: it could be a secret.
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatus unprocessable = HttpStatus.UNPROCESSABLE_CONTENT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(unprocessable, "The request has invalid fields.");
        problem.setProperty(CODE, VALIDATION_FAILED);
        problem.setProperty("errors", fieldErrors(ex));
        return handleExceptionInternal(ex, problem, headers, unprocessable, request);
    }

    // 422 for a query or path parameter too, named as the request spells it, such as created[gte]. A
    // value Spring cannot convert, such as a word for a number, stays a 400.
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatus unprocessable = HttpStatus.UNPROCESSABLE_CONTENT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(unprocessable, "The request has invalid fields.");
        problem.setProperty(CODE, VALIDATION_FAILED);
        problem.setProperty("errors", parameterErrors(ex));
        return handleExceptionInternal(ex, problem, headers, unprocessable, request);
    }

    // A field the request does not define is refused as invalid, not ignored, so a misspelled
    // optional field is not silently dropped. The rest of an unreadable body stays a 400.
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(ex.getCause() instanceof UnrecognizedPropertyException unknown)) {
            return super.handleHttpMessageNotReadable(ex, headers, status, request);
        }
        HttpStatus unprocessable = HttpStatus.UNPROCESSABLE_CONTENT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(unprocessable, "The request has invalid fields.");
        problem.setProperty(CODE, VALIDATION_FAILED);
        problem.setProperty("errors", List.of(new FieldProblem(jsonPath(unknown.getPath()), "is not a known field")));
        return handleExceptionInternal(ex, problem, headers, unprocessable, request);
    }

    // Jackson's path ends with the unknown field itself and spells names as the request does.
    static String jsonPath(List<JacksonException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : references) {
            @Nullable String name = reference.getPropertyName();
            if (name == null) {
                path.append('[').append(reference.getIndex()).append(']');
            } else {
                path.append(path.isEmpty() ? "" : ".").append(name);
            }
        }
        return path.toString();
    }

    private static List<FieldProblem> fieldErrors(MethodArgumentNotValidException ex) {
        Stream<FieldProblem> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldProblem(jsonPath(error.getField()), message(error.getDefaultMessage())));
        Stream<FieldProblem> objects = ex.getBindingResult().getGlobalErrors().stream()
                .filter(error -> !(error instanceof FieldError))
                .map(error -> new FieldProblem(error.getObjectName(), message(error.getDefaultMessage())));
        return Stream.concat(fields, objects)
                .sorted(Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::detail))
                .toList();
    }

    private static List<FieldProblem> parameterErrors(HandlerMethodValidationException ex) {
        return ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldProblem(
                                parameterName(result.getMethodParameter()), message(error.getDefaultMessage()))))
                .sorted(Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::detail))
                .toList();
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null && !query.name().isEmpty()) {
            return query.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.name().isEmpty()) {
            return path.name();
        }
        @Nullable String name = parameter.getParameterName();
        return name == null ? "arg" + parameter.getParameterIndex() : jsonPath(name);
    }

    // Java names to the contract's snake_case: items[0].unitPrice becomes items[0].unit_price.
    // Brackets are left alone: they hold an index or a client's map key.
    static String jsonPath(String javaPath) {
        StringBuilder path = new StringBuilder();
        Matcher segment = PATH_SEGMENT.matcher(javaPath);
        while (segment.find()) {
            String text = segment.group();
            path.append(
                    text.startsWith("[")
                            ? text
                            : CAMEL_HUMP.matcher(text).replaceAll("$1_$2").toLowerCase(Locale.ROOT));
        }
        return path.toString();
    }

    private static String message(@Nullable String message) {
        return message == null ? "is invalid" : message;
    }

    record FieldProblem(String field, String detail) {}

    // Every Spring MVC error passes here, so the default code is added in one place.
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && !hasCode(problem)) {
            problem.setProperty(CODE, defaultCode(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static boolean hasCode(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        return properties != null && properties.containsKey(CODE);
    }

    static String defaultCode(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status == null ? "error" : status.name().toLowerCase(Locale.ROOT);
    }
}
