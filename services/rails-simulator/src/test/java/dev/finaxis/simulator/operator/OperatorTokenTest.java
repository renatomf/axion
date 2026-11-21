package dev.finaxis.simulator.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.simulator.PostgresTestConfiguration;
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

/** The operator token of {@code src/test/resources/config/application.yml} opens the operator API, and nothing else does. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class OperatorTokenTest {

    private static final String TOKEN = "operator-token-for-tests-only-0000";

    private final MockMvcTester mvc;

    @Autowired
    OperatorTokenTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void authenticatesTheOperatorToken() {
        // Past authentication, an unknown path is a 404, not a 401.
        assertThat(mvc.get().uri("/no/such/path").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void acceptsTheSchemeInAnyCase() {
        assertThat(mvc.get().uri("/no/such/path").header(HttpHeaders.AUTHORIZATION, "bearer " + TOKEN))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Bearer operator-token-for-tests-only-0001",
                "Bearer operator-token-for-tests-only-000",
                "Bearer " + TOKEN + "0",
                "Bearer ",
                "Basic b3BlcmF0b3I6c2VjcmV0",
                TOKEN
            })
    void refusesAnyOtherAuthorization(String authorization) {
        assertThat(mvc.get().uri("/no/such/path").header(HttpHeaders.AUTHORIZATION, authorization))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void refusesARequestWithoutAToken() {
        assertThat(mvc.get().uri("/no/such/path"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void refusesATokenShorterThan32CharactersAtStartup() {
        assertThatThrownBy(() -> new OperatorProperties("a".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("aaaa");
    }

    @Test
    void neverPrintsTheToken() {
        assertThat(new OperatorProperties(TOKEN)).hasToString("OperatorProperties[token=***]");
    }
}
