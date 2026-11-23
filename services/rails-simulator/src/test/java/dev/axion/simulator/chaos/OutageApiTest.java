package dev.axion.simulator.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.simulator.PostgresTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The outage switch of {@code openapi/simulator.yaml}, over HTTP, with the operator token. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class OutageApiTest {

    private static final String AUTHORIZATION = "Bearer operator-token-for-tests-only-0000";
    private static final String MILLISECONDS = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z";

    private final MockMvcTester mvc;
    private final Outage outage;

    @Autowired
    OutageApiTest(MockMvcTester mvc, Outage outage) {
        this.mvc = mvc;
        this.outage = outage;
    }

    @AfterEach
    void railsBack() {
        outage.end();
    }

    @Test
    void takesTheRailsDownForADuration() {
        MvcTestResult started = start("{\"duration\": \"PT30S\"}");

        assertThat(started).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(started).bodyJson().extractingPath("$.startedAt").asString().matches(MILLISECONDS);
        assertThat(started).bodyJson().extractingPath("$.endsAt").asString().matches(MILLISECONDS);
        assertThat(outage.current())
                .hasValueSatisfying(down ->
                        assertThat(down.endsAt()).isEqualTo(down.startedAt().plusSeconds(30)));
    }

    @Test
    void takesTheRailsDownUntilTheyAreBroughtBack() {
        MvcTestResult started = start("{}");

        assertThat(started).bodyJson().doesNotHavePath("$.endsAt");
        assertThat(outage.current()).isPresent();
        assertThat(end()).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(outage.current()).isEmpty();
    }

    @Test
    void endsNoOutageWithoutError() {
        assertThat(end()).hasStatus(HttpStatus.NO_CONTENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT3600.001S", "30s", "PT1M"})
    void refusesADurationOutOfItsLimits(String duration) {
        assertThat(start("{\"duration\": \"" + duration + "\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("duration must be an ISO 8601 duration of seconds, more than zero and at most PT3600S.");
        assertThat(outage.current()).isEmpty();
    }

    @Test
    void requiresTheOperatorToken() {
        assertThat(mvc.put()
                        .uri("/chaos/outage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.delete().uri("/chaos/outage")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(outage.current()).isEmpty();
    }

    private MvcTestResult start(String body) {
        return mvc.put()
                .uri("/chaos/outage")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult end() {
        return mvc.delete()
                .uri("/chaos/outage")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange();
    }
}
