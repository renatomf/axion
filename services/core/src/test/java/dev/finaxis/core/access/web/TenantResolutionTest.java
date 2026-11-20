package dev.finaxis.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.KeycloakRealm;
import dev.finaxis.core.KeycloakTestConfiguration;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.RequiresStepUp;
import dev.finaxis.core.shared.PublicId;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How a member's request reaches a tenant-scoped endpoint, with real Keycloak tokens. {@link
 * TenantScopedController} stands in for real endpoints, so one of its methods can lack a
 * permission, which production code may not.
 */
@SpringBootTest
@Import({
    PostgresTestConfiguration.class,
    KeycloakTestConfiguration.class,
    TenantResolutionTest.TenantScopedController.class
})
@AutoConfigureMockMvc
class TenantResolutionTest {

    @RestController
    static class TenantScopedController {

        /** Answers with what it received, so a test can check the caller. */
        @RequiresPermission(Permission.API_KEYS_MANAGE)
        @GetMapping("/internal/tenants/{tenant_id}/probe")
        String probe(MemberCaller caller) {
            return caller.tenant().publicId() + " " + caller.environment() + " "
                    + caller.member().value();
        }

        /** Answers with the tenant bound for Row-Level Security. */
        @RequiresPermission(Permission.API_KEYS_MANAGE)
        @GetMapping("/internal/tenants/{tenant_id}/bound")
        String bound() {
            return TenantContext.current()
                    .map(tenant -> tenant.publicId().toString())
                    .orElse("none");
        }

        /** Needs a recent authentication too; {@link StepUpTest} calls it. */
        @RequiresPermission(Permission.API_KEYS_MANAGE)
        @RequiresStepUp
        @GetMapping("/internal/tenants/{tenant_id}/sensitive")
        String sensitive(MemberCaller caller) {
            return "reached";
        }

        /** Declares no permission, which the architecture test forbids in production code. */
        @GetMapping("/internal/tenants/{tenant_id}/undeclared")
        String undeclared() {
            return "reached";
        }
    }

    private final MockMvcTester mvc;
    private final KeycloakRealm realm;
    private final TenancyApi tenancy;
    private final JdbcClient owner;

    private String accessToken = "";
    private String subject = "";

    @Autowired
    TenantResolutionTest(MockMvcTester mvc, KeycloakRealm realm, TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.mvc = mvc;
        this.realm = realm;
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @BeforeEach
    void logIn() throws ParseException {
        accessToken = realm.login(KeycloakRealm.TEST_CLIENT).accessToken();
        subject = SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();
    }

    @Test
    void passesTheTenantItsEnvironmentAndTheMemberToTheEndpoint() {
        NewOrganization created = tenancy.createOrganization("Acme", subject);

        probe(created.live().publicId().toString())
                .hasStatus(HttpStatus.OK)
                .hasBodyTextEqualTo(
                        created.live().publicId() + " LIVE " + created.admin().value());
        probe(created.test().publicId().toString())
                .hasStatus(HttpStatus.OK)
                .hasBodyTextEqualTo(
                        created.test().publicId() + " TEST " + created.admin().value());
    }

    @Test
    void bindsTheTenantInThePathOnceTheMembershipIsChecked() {
        NewOrganization created = tenancy.createOrganization("Acme", subject);

        assertThat(mvc.get()
                        .uri(
                                "/internal/tenants/{tenant_id}/bound",
                                created.live().publicId())
                        .header("Authorization", "Bearer " + accessToken))
                .hasStatus(HttpStatus.OK)
                .hasBodyTextEqualTo(created.live().publicId().toString());
    }

    @Test
    void hidesATenantOfAnotherOrganization() {
        tenancy.createOrganization("Acme", subject);
        NewOrganization others =
                tenancy.createOrganization("Someone else's", UUID.randomUUID().toString());

        assertProblem(probe(others.live().publicId().toString()), HttpStatus.NOT_FOUND, "not_found");
    }

    @Test
    void answersATenantThatDoesNotExistLikeAnotherOrganizationsTenant() {
        tenancy.createOrganization("Acme", subject);
        var unknown = new PublicId(TenantId.PREFIX, UUID.randomUUID());

        assertProblem(probe(unknown.toString()), HttpStatus.NOT_FOUND, "not_found");
    }

    @Test
    void answersAMalformedTenantIdLikeAnUnknownTenant() {
        NewOrganization created = tenancy.createOrganization("Acme", subject);
        // The right UUID with the organization's prefix: a valid ID, of the wrong type.
        var organizationId = created.organization().publicId().toString();

        assertProblem(probe(organizationId), HttpStatus.NOT_FOUND, "not_found");
        assertProblem(probe("not-an-id"), HttpStatus.NOT_FOUND, "not_found");
    }

    @Test
    void refusesATenantThatIsNotActive() {
        NewOrganization created = tenancy.createOrganization("Acme", subject);
        owner.sql("UPDATE tenancy.tenants SET status = 'SUSPENDED' WHERE id = ?")
                .param(created.live().value())
                .update();

        assertProblem(probe(created.live().publicId().toString()), HttpStatus.FORBIDDEN, "tenant_inactive");
    }

    @Test
    void refusesAMemberWithoutThePermission() {
        NewOrganization created = tenancy.createOrganization("Acme", subject);
        owner.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
                .param(created.admin().value())
                .update();

        assertProblem(probe(created.test().publicId().toString()), HttpStatus.FORBIDDEN, "insufficient_permission");
    }

    @Test
    void deniesAnEndpointThatDeclaresNoPermission() {
        NewOrganization created = tenancy.createOrganization("Acme", subject);

        var result = assertThat(mvc.get()
                .uri("/internal/tenants/{tenant_id}/undeclared", created.test().publicId())
                .header("Authorization", "Bearer " + accessToken));

        assertProblem(result, HttpStatus.FORBIDDEN, "insufficient_permission");
    }

    private MvcTestResultAssert probe(String tenantId) {
        return assertThat(mvc.get()
                .uri("/internal/tenants/{tenant_id}/probe", tenantId)
                .header("Authorization", "Bearer " + accessToken));
    }

    private static void assertProblem(MvcTestResultAssert result, HttpStatus status, String code) {
        result.hasStatus(status).bodyJson().extractingPath("$.code").isEqualTo(code);
    }
}
