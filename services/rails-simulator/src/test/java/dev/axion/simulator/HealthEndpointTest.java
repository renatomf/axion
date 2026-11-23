package dev.axion.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The health endpoint and its probes, the only endpoints the skeleton exposes. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
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
    @WithMockUser
    void doesNotExposeOtherEndpointsEvenToAnOperator() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
