package dev.finaxis.core.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception raised while handling a request into an RFC 9457 Problem Details response
 * ({@code application/problem+json}).
 *
 * <p>{@code @RestControllerAdvice} makes Spring MVC call this bean's exception handlers for every
 * controller. {@link ResponseEntityExceptionHandler} already handles the exceptions Spring MVC itself
 * raises, such as an unknown path ({@code 404}) or an unsupported HTTP method ({@code 405}), and
 * returns them as a {@code ProblemDetail}. Spring Boot backs off its own equivalent handler when this
 * bean exists, so there is exactly one place that decides what an error looks like.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {}
