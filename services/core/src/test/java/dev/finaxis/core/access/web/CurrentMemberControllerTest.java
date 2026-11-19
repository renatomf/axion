package dev.finaxis.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import dev.finaxis.core.KeycloakRealm;
import dev.finaxis.core.KeycloakTestConfiguration;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.tenancy.TenancyApi;
import java.text.ParseException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /internal/me} with a real access token from Keycloak and a real PostgreSQL.
 *
 * <p>Every test logs in as the same synthetic Keycloak user, so the organizations one test creates
 * would show up in the next. {@code @Transactional} runs each test in a transaction that is rolled
 * back at its end. MockMvc handles the request in the test's own thread, so the request's queries
 * join that transaction and see what the test wrote before it.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class})
@AutoConfigureMockMvc
@Transactional
class CurrentMemberControllerTest {

    private final MockMvcTester mvc;
    private final KeycloakRealm realm;
    private final TenancyApi tenancy;

    private String accessToken = "";
    private String subject = "";

    @Autowired
    CurrentMemberControllerTest(MockMvcTester mvc, KeycloakRealm realm, TenancyApi tenancy) {
        this.mvc = mvc;
        this.realm = realm;
        this.tenancy = tenancy;
    }

    @BeforeEach
    void logIn() throws ParseException {
        accessToken = realm.login(KeycloakRealm.TEST_CLIENT).accessToken();
        subject = SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();
    }

    private MvcTestResultAssert me() {
        return assertThat(mvc.get().uri("/internal/me").header("Authorization", "Bearer " + accessToken));
    }

    @Test
    void listsTheCallersOrganizationsWithTheirTenants() {
        var created = tenancy.createOrganization("Acme", subject);

        me().hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {"organizations": [{
                  "id": "%s",
                  "name": "Acme",
                  "roles": ["admin"],
                  "tenants": [
                    {"id": "%s", "environment": "test", "status": "active"},
                    {"id": "%s", "environment": "live", "status": "active"}
                  ]
                }]}
                """.formatted(
                        created.organization().publicId(),
                        created.test().publicId(),
                        created.live().publicId()));
    }

    @Test
    void listsNoOrganizationOfAnotherPerson() {
        tenancy.createOrganization("Someone else's", UUID.randomUUID().toString());

        me().hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {"organizations": []}
                """);
    }
}
