package dev.finaxis.simulator.operator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.simulator.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class SecurityConfigurationTest {

    private final MockMvcTester mvc;

    @Autowired
    SecurityConfigurationTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void rejectsAnUnauthenticatedRequestWithProblemDetails() {
        assertThat(mvc.get().uri("/pix/payments/anything"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("Authentication is required.");
    }

    @Test
    void leavesTheHealthEndpointOpen() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    @Test
    void forbidsBrowsersFromLoadingOrFramingAResponse() {
        assertThat(mvc.get().uri("/actuator/health"))
                .headers()
                .hasValue("Content-Security-Policy", SecurityConfiguration.CONTENT_SECURITY_POLICY)
                .hasValue("Referrer-Policy", "no-referrer");
    }
}
