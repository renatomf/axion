package dev.finaxis.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The correlation ID filter on its own, without a Spring context. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<@Nullable String> idInMdc = new AtomicReference<>();

    /** The rest of the chain: records the ID in the MDC while the request is handled. */
    private final FilterChain chain = (req, res) -> idInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY));

    @Test
    void reusesAValidIncomingId() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "client-req_42.a");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-req_42.a");
        assertThat(idInMdc.get()).isEqualTo("client-req_42.a");
    }

    @Test
    void generatesAnIdWhenNoneIsSent() throws Exception {
        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotNull();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(idInMdc.get()).isEqualTo(generated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "has space", "line\nbreak", "<script>"})
    void replacesAnInvalidIncomingId(String invalid) throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, invalid);

        filter.doFilter(request, response, chain);

        String replacement = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(replacement).isNotEqualTo(invalid);
        assertThat(UUID.fromString(replacement)).isNotNull();
    }

    @Test
    void acceptsAnIdOfUpTo64Characters() throws Exception {
        String longest = "x".repeat(64);
        request.addHeader(CorrelationIdFilter.HEADER, longest);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(longest);
    }

    @Test
    void replacesAnIdLongerThan64Characters() throws Exception {
        String tooLong = "x".repeat(65);
        request.addHeader(CorrelationIdFilter.HEADER, tooLong);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(tooLong);
    }

    @Test
    void clearsTheMdcAfterTheRequest() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(idInMdc.get()).isNotNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
