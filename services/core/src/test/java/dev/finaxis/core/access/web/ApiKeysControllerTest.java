package dev.finaxis.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.finaxis.core.KeycloakTestConfiguration;
import dev.finaxis.core.OpenApiContract;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.IssueResult;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.Role;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /internal/tenants/{tenant_id}/api_keys} against a real PostgreSQL, every response
 * checked against the contract.
 *
 * <p>The member's token is built with Spring Security's {@code jwt()}, which skips decoding and
 * validation: real tokens are proven in {@link TenantResolutionTest}, and here only the subject
 * matters. Each test runs in a transaction rolled back at its end, as in {@link
 * CurrentMemberControllerTest}, with which it shares a Spring context.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class})
@AutoConfigureMockMvc
@Transactional
class ApiKeysControllerTest {

    private static final Instant MOMENT = Instant.parse("2026-11-19T12:00:00Z");

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient jdbc;

    private String subject = "";
    private NewOrganization organization;

    @Autowired
    ApiKeysControllerTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy, JdbcClient jdbc) {
        this.mvc = mvc;
        this.access = access;
        this.tenancy = tenancy;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void createOrganization() {
        subject = UUID.randomUUID().toString();
        organization = tenancy.createOrganization("Acme", subject);
    }

    @Test
    void listsAKeyWithoutItsSecret() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");
        jdbc.sql("UPDATE access.api_keys SET created_at = ?, last_used_at = ? WHERE id = ?")
                .params(utc(MOMENT), utc(MOMENT.plusSeconds(3600)), key.id().value())
                .update();

        list(organization.test()).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {"data": [{
                  "id": "%s",
                  "name": "Checkout",
                  "last_four": "%s",
                  "scopes": ["charges:read", "charges:write"],
                  "created_at": "2026-11-19T12:00:00Z",
                  "expires_at": null,
                  "revoked_at": null,
                  "last_used_at": "2026-11-19T13:00:00Z"
                }]}
                """.formatted(
                        key.id().publicId(), key.lastFour()));
    }

    @Test
    void listsOnlyTheTenantsKeysNewestFirst() {
        IssuedApiKey first = issued(organization, Environment.TEST, "First");
        IssuedApiKey second = issued(organization, Environment.TEST, "Second");
        // The organization's other environment is another tenant.
        issued(organization, Environment.LIVE, "Live");

        list(organization.test())
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.data[*].id")
                .isEqualTo(List.of(
                        second.id().publicId().toString(), first.id().publicId().toString()));
    }

    @Test
    void listsARevokedKeyWithWhenItWasRevoked() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");
        jdbc.sql("UPDATE access.api_keys SET revoked_at = ? WHERE id = ?")
                .params(utc(MOMENT), key.id().value())
                .update();

        list(organization.test())
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.data[0].revoked_at")
                .isEqualTo("2026-11-19T12:00:00Z");
    }

    @Test
    void listsNothingForATenantWithoutKeys() {
        list(organization.live()).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {"data": []}
                """);
    }

    @Test
    void refusesAMemberWhoCannotManageKeys() {
        jdbc.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
                .param(organization.admin().value())
                .update();

        list(organization.test())
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    @Test
    void hidesTheKeysOfAnotherOrganization() {
        NewOrganization other =
                tenancy.createOrganization("Other", UUID.randomUUID().toString());
        issued(other, Environment.TEST, "Theirs");

        list(other.test())
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }

    @Test
    void refusesARequestWithoutAToken() {
        MvcTestResult result = mvc.get()
                .uri(
                        "/internal/tenants/{tenant_id}/api_keys",
                        organization.test().publicId())
                .exchange();
        OpenApiContract.internal().assertConforms(result);

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** Lists the tenant's keys as the member, and checks the response against the contract. */
    private MvcTestResultAssert list(TenantId tenant) {
        MvcTestResult result = mvc.get()
                .uri("/internal/tenants/{tenant_id}/api_keys", tenant.publicId())
                .with(jwt().jwt(token -> token.subject(subject)))
                .exchange();
        OpenApiContract.internal().assertConforms(result);
        return assertThat(result);
    }

    /** Issues a key through the module API, as the organization's admin, in this environment. */
    private IssuedApiKey issued(NewOrganization owner, Environment environment, String name) {
        TenantId tenant = environment == Environment.TEST ? owner.test() : owner.live();
        var admin = new MemberCaller(tenant, environment, owner.admin(), Role.ADMIN.permissions());
        IssueResult result = access.issue(admin, name, Set.of(Permission.CHARGES_READ, Permission.CHARGES_WRITE));
        assertThat(result).isInstanceOf(IssueResult.Issued.class);
        return ((IssueResult.Issued) result).key();
    }

    private static Object utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
