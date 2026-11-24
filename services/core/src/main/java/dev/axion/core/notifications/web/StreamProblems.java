package dev.axion.core.notifications.web;

import dev.axion.core.notifications.application.LiveStreams;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The errors of the live stream. */
final class StreamProblems {

    private StreamProblems() {}

    // 429: open streams are a budget of the caller's, which closing one frees.
    static ErrorResponseException memberLimitReached() {
        return tooManyStreams("The member already has " + LiveStreams.PER_MEMBER + " live streams open.");
    }

    static ErrorResponseException tenantLimitReached() {
        return tooManyStreams("The tenant already has " + LiveStreams.PER_TENANT + " live streams open.");
    }

    private static ErrorResponseException tooManyStreams(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, detail);
        problem.setProperty("code", "too_many_streams");
        return new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, problem, null);
    }
}
