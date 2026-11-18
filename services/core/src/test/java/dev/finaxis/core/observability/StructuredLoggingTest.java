package dev.finaxis.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.finaxis.core.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Logs as configured in {@code application.yml}: what a log line written while a request is handled
 * looks like on the console.
 *
 * <p>The test uses the filter bean from the application context and logs from inside the filter
 * chain, as a controller would. {@link OutputCaptureExtension} records what is written to the
 * console and hands it to the test as {@link CapturedOutput}.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredLoggingTest.class);

    private final CorrelationIdFilter filter;

    @Autowired
    StructuredLoggingTest(CorrelationIdFilter filter) {
        this.filter = filter;
    }

    @Test
    void logLinesDuringARequestAreJsonWithTheCorrelationId(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "log-test-1");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> log.info("handling the request"));

        String line = output.getOut()
                .lines()
                .filter(l -> l.contains("handling the request"))
                .findFirst()
                .orElseThrow();
        // JsonPath fails if the line is not valid JSON.
        assertThat(JsonPath.<String>read(line, "$.message")).isEqualTo("handling the request");
        assertThat(JsonPath.<String>read(line, "$.correlation_id")).isEqualTo("log-test-1");
        assertThat(JsonPath.<String>read(line, "$.service.name")).isEqualTo("finaxis-core");
    }
}
