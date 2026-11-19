package dev.finaxis.core.access.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The HTTP security of the core, through the real filter chain.
 *
 * <p>{@code @SpringBootTest} starts the whole application, so requests pass through Spring
 * Security, Actuator and the error handling exactly as in production. {@code @WithMockUser} (from
 * Spring Security's test support) runs a test as an authenticated user, without any login.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
class SecurityConfigurationTest {

    private final MockMvcTester mvc;
    private final ApplicationContext context;

    @Autowired
    SecurityConfigurationTest(MockMvcTester mvc, ApplicationContext context) {
        this.mvc = mvc;
        this.context = context;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void healthEndpointIsPublic(String path) {
        assertThat(mvc.get().uri(path)).hasStatusOk();
    }

    /** Existing or not, a path gives an unauthenticated caller the same answer. */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/v1/charges", "/internal/tenants", "/actuator/env"})
    void everyOtherRequestRequiresAuthentication(String path) {
        assertThat(mvc.get().uri(path))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status": 401, "detail": "Authentication is required.", "code": "unauthorized"}
                        """);
    }

    /** Deny by default: being authenticated is not enough without a rule that opens the path. */
    @Test
    @WithMockUser
    void authenticatedRequestWithoutAnExplicitRuleIsForbidden() {
        assertThat(mvc.get().uri("/v1/charges"))
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status": 403, "detail": "Access is denied.", "code": "forbidden"}
                        """);
    }

    /** A {@code /logout} endpoint would answer with a redirect to a login page. */
    @Test
    void thereIsNoLogoutEndpoint() {
        assertThat(mvc.post().uri("/logout")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noHttpSessionIsCreated() {
        MvcTestResult result = mvc.get().uri("/v1/charges").exchange();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    /** No user store means no generated password in the logs. */
    @Test
    void thereIsNoInMemoryUser() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    /** Errors included: an attacker's first look at an API is usually a rejected request. */
    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/v1/charges"})
    void everyResponseCarriesSecurityHeaders(String path) {
        assertThat(mvc.get().uri(path))
                .headers()
                .hasValue("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .hasValue("Referrer-Policy", "no-referrer")
                .hasValue("X-Content-Type-Options", "nosniff")
                .hasValue("X-Frame-Options", "DENY")
                .containsHeader("Cache-Control");
    }
}
