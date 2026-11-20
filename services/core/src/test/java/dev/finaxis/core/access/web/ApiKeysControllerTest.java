package dev.finaxis.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.finaxis.core.KeycloakTestConfiguration;
import dev.finaxis.core.OpenApiContract;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.IssueResult;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.Role;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Duration;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET} and {@code POST /internal/tenants/{tenant_id}/api_keys}, and {@code POST
 * …/api_keys/{key_id}/revoke}, against a real PostgreSQL, every response checked against the
 * contract.
 *
 * <p>The member's token is built with Spring Security's {@code jwt()}, which skips decoding and
 * validation: real tokens are proven in {@link TenantResolutionTest}, and here only the subject
 * matters, and, for issuing and revoking, a recent {@code auth_time}, which step-up requires. Each test runs in a transaction rolled back at its end, as in {@link
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

    @Test
    void issuesAKeyAndShowsItsSecretOnlyOnce() {
        MvcTestResult result = issue(organization.test(), """
                {"name": "Checkout", "scopes": ["charges:write", "charges:read"]}
                """);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Checkout");
        assertThat(result).bodyJson().extractingPath("$.scopes").isEqualTo(List.of("charges:read", "charges:write"));
        assertThat(result).bodyJson().extractingPath("$.secret").asString().startsWith("sk_test_");

        // The contract's ApiKey has no secret, and objects are checked strictly, so a list that
        // leaked it would fail the contract check in list().
        list(organization.test())
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.data[*].name")
                .isEqualTo(List.of("Checkout"));
    }

    @Test
    void issuesALiveKeyForTheLiveTenant() {
        assertThat(issue(organization.live(), """
                        {"name": "Checkout", "scopes": ["charges:read"]}
                        """))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.secret")
                .asString()
                .startsWith("sk_live_");
    }

    @Test
    void listsEveryInvalidFieldWithoutEchoingIt() {
        MvcTestResult result = issue(organization.test(), """
                {"name": " ", "scopes": ["charges:read", "api_keys:manage"]}
                """);

        // Leniently: the problem's title, status, detail and instance are not the point here.
        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "validation_failed", "errors": [
                          {"field": "name", "detail": "must be 1 to 100 characters, not all blank"},
                          {"field": "scopes[1]", "detail": "must be an API key scope"}
                        ]}
                        """);
        assertThat(result).body().asString().doesNotContain("api_keys:manage");
    }

    @Test
    void rejectsABodyWithoutTheRequiredFields() {
        assertThat(issue(organization.test(), "{}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("name", "scopes"));
    }

    @Test
    void rejectsABodyThatIsNotJson() {
        assertThat(issue(organization.test(), "name=Checkout"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("bad_request");
    }

    @Test
    void refusesScopesTheMemberDoesNotHold() {
        // A developer may manage keys, but not issue refunds.
        jdbc.sql("UPDATE tenancy.member_roles SET role = 'DEVELOPER' WHERE member_id = ?")
                .param(organization.admin().value())
                .update();

        assertThat(issue(organization.test(), """
                        {"name": "Refunds", "scopes": ["refunds:write"]}
                        """))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    @Test
    void refusesAKeyBeyondTheTenantsLimit() {
        for (int i = 0; i < AccessApi.MAX_ACTIVE_KEYS; i++) {
            issued(organization, Environment.TEST, "Key " + i);
        }

        assertThat(issue(organization.test(), """
                        {"name": "One too many", "scopes": ["charges:read"]}
                        """))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("limit_exceeded");
    }

    @Test
    void checksThePermissionBeforeTheBody() {
        jdbc.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
                .param(organization.admin().value())
                .update();

        // An invalid body, yet the answer is about the permission: the body is never read.
        assertThat(issue(organization.test(), "{}"))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    @Test
    void requiresARecentAuthentication() {
        MvcTestResult result = mvc.post()
                .uri(
                        "/internal/tenants/{tenant_id}/api_keys",
                        organization.test().publicId())
                .with(jwt().jwt(token -> token.subject(subject)
                        .claim(
                                "auth_time",
                                Instant.now().minus(Duration.ofMinutes(6)).getEpochSecond())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Checkout", "scopes": ["charges:read"]}
                        """)
                .exchange();
        OpenApiContract.internal().assertConforms(result);

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("step_up_required");
    }

    @Test
    void revokesAKeyAndListsItAsRevoked() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");

        MvcTestResult result = revoke(organization.test(), key.id().publicId().toString(), Duration.ofMinutes(1));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.id")
                .isEqualTo(key.id().publicId().toString());
        assertThat(result).bodyJson().extractingPath("$.revoked_at").isNotNull();
        list(organization.test())
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.data[0].revoked_at")
                .isNotNull();
    }

    @Test
    void keepsTheFirstRevocationWhenRevokedAgain() throws Exception {
        String key = issued(organization, Environment.TEST, "Checkout")
                .id()
                .publicId()
                .toString();
        MvcTestResult first = revoke(organization.test(), key, Duration.ofMinutes(1));

        MvcTestResult second = revoke(organization.test(), key, Duration.ofMinutes(1));

        assertThat(second).hasStatus(HttpStatus.OK);
        // The same body, down to revoked_at: the second request changed nothing.
        assertThat(second).bodyJson().isStrictlyEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void hidesAKeyOfAnotherTenantWhenRevoking() {
        // The organization's live environment is another tenant than the one in the path.
        IssuedApiKey live = issued(organization, Environment.LIVE, "Live");

        assertThat(revoke(organization.test(), live.id().publicId().toString(), Duration.ofMinutes(1)))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
        assertThat(access.keys(organization.live()).getFirst().revokedAt()).isEmpty();
    }

    @Test
    void findsNoKeyToRevokeForAnUnknownOrMalformedId() {
        String unknown = new ApiKeyId(UUID.randomUUID()).publicId().toString();

        for (String id :
                List.of(unknown, "key_not-an-id", organization.test().publicId().toString())) {
            assertThat(revoke(organization.test(), id, Duration.ofMinutes(1)))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("not_found");
        }
    }

    @Test
    void refusesToRevokeForAMemberWhoCannotManageKeys() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");
        jdbc.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
                .param(organization.admin().value())
                .update();

        assertThat(revoke(organization.test(), key.id().publicId().toString(), Duration.ofMinutes(1)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    @Test
    void requiresARecentAuthenticationToRevoke() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");

        assertThat(revoke(organization.test(), key.id().publicId().toString(), Duration.ofMinutes(6)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("step_up_required");
        assertThat(access.keys(organization.test()).getFirst().revokedAt()).isEmpty();
    }

    /**
     * Revokes a key as the member, who authenticated this long ago, and checks the response against
     * the contract.
     */
    private MvcTestResult revoke(TenantId tenant, String keyId, Duration sinceAuthentication) {
        MvcTestResult result = mvc.post()
                .uri("/internal/tenants/{tenant_id}/api_keys/{key_id}/revoke", tenant.publicId(), keyId)
                .with(jwt().jwt(token -> token.subject(subject)
                        .claim(
                                "auth_time",
                                Instant.now().minus(sinceAuthentication).getEpochSecond())))
                .exchange();
        OpenApiContract.internal().assertConforms(result);
        return result;
    }

    /**
     * Issues a key as the member, who authenticated a minute ago, and checks the response against the
     * contract.
     */
    private MvcTestResult issue(TenantId tenant, String body) {
        MvcTestResult result = mvc.post()
                .uri("/internal/tenants/{tenant_id}/api_keys", tenant.publicId())
                .with(jwt().jwt(token -> token.subject(subject)
                        .claim(
                                "auth_time",
                                Instant.now().minus(Duration.ofMinutes(1)).getEpochSecond())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        OpenApiContract.internal().assertConforms(result);
        return result;
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
