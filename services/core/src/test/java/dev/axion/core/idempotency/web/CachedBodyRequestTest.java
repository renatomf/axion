package dev.axion.core.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.ErrorResponseException;

class CachedBodyRequestTest {

    private static final int LIMIT = 8;

    @Test
    void servesTheBodyAgainAsReadBefore() throws IOException {
        var request = CachedBodyRequest.read(request("{\"a\":1}".getBytes(StandardCharsets.UTF_8), true), LIMIT);

        assertThat(request.getInputStream().readAllBytes()).asString().isEqualTo("{\"a\":1}");
        assertThat(request.getReader().readLine()).isEqualTo("{\"a\":1}");
        assertThat(request.body()).asString().isEqualTo("{\"a\":1}");
        assertThat(request.getContentLengthLong()).isEqualTo(7);
    }

    @Test
    void acceptsABodyOfTheLimit() throws IOException {
        assertThat(CachedBodyRequest.read(request(new byte[LIMIT], true), LIMIT).body())
                .hasSize(LIMIT);
    }

    @Test
    void refusesABodyOverTheLimitByItsDeclaredLength() {
        assertTooLarge(request(new byte[LIMIT + 1], true));
    }

    @Test
    void refusesABodyOverTheLimitWithoutADeclaredLength() {
        assertTooLarge(request(new byte[LIMIT + 1], false));
    }

    private static void assertTooLarge(MockHttpServletRequest request) {
        var problem = catchThrowableOfType(ErrorResponseException.class, () -> CachedBodyRequest.read(request, LIMIT));

        assertThat(problem.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(problem.getBody().getProperties()).containsEntry("code", "content_too_large");
    }

    /** A request with the body, sent with its length or, as a chunked body is, without it. */
    private static MockHttpServletRequest request(byte[] body, boolean declaresLength) {
        var request = new MockHttpServletRequest("POST", "/v1/charges") {
            @Override
            public long getContentLengthLong() {
                return declaresLength ? super.getContentLengthLong() : -1;
            }
        };
        request.setContent(body);
        return request;
    }
}
