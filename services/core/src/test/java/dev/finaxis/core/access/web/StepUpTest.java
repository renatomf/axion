package dev.finaxis.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.nimbusds.jwt.SignedJWT;
import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.KeycloakRealm;
import dev.finaxis.core.KeycloakTestConfiguration;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

/**
 * Step-up (tenancy design, section 4.2). Keycloak's password grant issues tokens without {@code
 * auth_time}, so one test uses a real token, which must be challenged, and the others choose their
 * {@code auth_time} on a token that skips decoding.
 */
@SpringBootTest
@Import({
    PostgresTestConfiguration.class,
    KeycloakTestConfiguration.class,
    TenantResolutionTest.TenantScopedController.class
})
@AutoConfigureMockMvc
class StepUpTest {

    private static final String CHALLENGE = "Bearer error=\"insufficient_user_authentication\", "
            + "error_description=\"A recent authentication is required\", max_age=\"300\"";

    private final MockMvcTester mvc;
    private final KeycloakRealm realm;
    private final TenancyApi tenancy;
    private final JdbcClient owner;

    @Autowired
    StepUpTest(MockMvcTester mvc, KeycloakRealm realm, TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.mvc = mvc;
        this.realm = realm;
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @Test
    void reachesTheOperationAfterARecentAuthentication() {
        String subject = UUID.randomUUID().toString();
        NewOrganization created = tenancy.createOrganization("Acme", subject);

        sensitive(created, subject, Instant.now().minus(Duration.ofMinutes(1)))
                .hasStatus(HttpStatus.OK)
                .hasBodyTextEqualTo("reached");
    }

    @Test
    void challengesAnAuthenticationOlderThanFiveMinutes() {
        String subject = UUID.randomUUID().toString();
        NewOrganization created = tenancy.createOrganization("Acme", subject);

        assertChallenged(sensitive(created, subject, Instant.now().minus(Duration.ofMinutes(6))));
    }

    @Test
    void challengesARealTokenWithoutAuthenticationTime() {
        String accessToken = realm.login(KeycloakRealm.TEST_CLIENT).accessToken();
        String subject = subjectOf(accessToken);
        NewOrganization created = tenancy.createOrganization("Acme", subject);

        assertChallenged(assertThat(mvc.get()
                .uri("/internal/tenants/{tenant_id}/sensitive", created.test().publicId())
                .header("Authorization", "Bearer " + accessToken)));
    }

    @Test
    void refusesAMissingPermissionBeforeAskingForStepUp() {
        String subject = UUID.randomUUID().toString();
        NewOrganization created = tenancy.createOrganization("Acme", subject);
        owner.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
                .param(created.admin().value())
                .update();

        // Re-authenticating would not help a member who lacks the permission, so no challenge.
        sensitive(created, subject, Instant.now().minus(Duration.ofMinutes(6)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .doesNotContainHeader(HttpHeaders.WWW_AUTHENTICATE)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    private MvcTestResultAssert sensitive(NewOrganization created, String subject, Instant authTime) {
        return assertThat(mvc.get()
                .uri("/internal/tenants/{tenant_id}/sensitive", created.test().publicId())
                // auth_time is a number of seconds in the token, as Keycloak writes it.
                .with(jwt().jwt(token -> token.subject(subject).claim("auth_time", authTime.getEpochSecond()))));
    }

    private static void assertChallenged(MvcTestResultAssert result) {
        result.hasStatus(HttpStatus.FORBIDDEN)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("step_up_required");
    }

    private static String subjectOf(String accessToken) {
        try {
            return SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();
        } catch (ParseException e) {
            throw new IllegalStateException(e);
        }
    }
}
