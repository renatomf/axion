package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The health endpoint as Actuator exposes it, without security, which would answer {@code 401} for
 * a closed and a missing endpoint alike; {@code SecurityConfigurationTest} covers it with security.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthEndpointTest {

    private final MockMvcTester mvc;

    @Autowired
    HealthEndpointTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void reportsUp() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void hidesComponentDetails() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk().bodyJson().doesNotHavePath("$.components");
    }

    @Test
    void exposesLivenessAndReadinessProbes() {
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
    }

    @Test
    void doesNotExposeOtherEndpoints() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
