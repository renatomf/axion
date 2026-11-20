package dev.finaxis.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.KeycloakTestConfiguration;
import dev.finaxis.core.OpenApiContract;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

/**
 * The API key endpoints of {@code /internal}, every response checked against the contract. Tokens
 * skip decoding: real ones are proven in {@link TenantResolutionTest}.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class})
@AutoConfigureMockMvc
class ApiKeysControllerTest {

    private static final Instant MOMENT = Instant.parse("2026-11-19T12:00:00Z");

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;

    private String subject = "";
    private NewOrganization organization;

    @Autowired
    ApiKeysControllerTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access);
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @BeforeEach
    void createOrganization() {
        subject = UUID.randomUUID().toString();
        organization = tenancy.createOrganization("Acme", subject);
    }

    @Test
    void listsAKeyWithoutItsSecret() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");
        owner.sql("UPDATE access.api_keys SET created_at = ?, last_used_at = ? WHERE id = ?")
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
        owner.sql("UPDATE access.api_keys SET revoked_at = ? WHERE id = ?")
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
        owner.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
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
        owner.sql("UPDATE tenancy.member_roles SET role = 'DEVELOPER' WHERE member_id = ?")
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
        owner.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
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
        owner.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
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

    @Test
    void rollsAKeyKeepingItsNameAndScopesForADayByDefault() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");
        Instant before = Instant.now();

        MvcTestResult result = roll(organization.test(), key.id().publicId().toString(), null, Duration.ofMinutes(1));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.replacement.id")
                .isNotEqualTo(key.id().publicId().toString());
        assertThat(result).bodyJson().extractingPath("$.replacement.name").isEqualTo("Checkout");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.replacement.scopes")
                .isEqualTo(List.of("charges:read", "charges:write"));
        assertThat(result)
                .bodyJson()
                .extractingPath("$.replacement.secret")
                .asString()
                .startsWith("sk_test_");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.rolled.id")
                .isEqualTo(key.id().publicId().toString());
        assertThat(expiresAt(key))
                .isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
    }

    @Test
    void rollsWithTheGracePeriodTheRequestNames() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");
        Instant before = Instant.now();

        assertThat(roll(organization.test(), key.id().publicId().toString(), """
                        {"grace_period_seconds": 604800}
                        """, Duration.ofMinutes(1)))
                .hasStatus(HttpStatus.CREATED);

        assertThat(expiresAt(key))
                .isBetween(before.plus(Duration.ofDays(7)), Instant.now().plus(Duration.ofDays(7)));
    }

    @Test
    void rejectsAGracePeriodOutOfRange() {
        String key = issued(organization, Environment.TEST, "Checkout")
                .id()
                .publicId()
                .toString();

        for (String body : List.of("{\"grace_period_seconds\": -1}", "{\"grace_period_seconds\": 604801}")) {
            assertThat(roll(organization.test(), key, body, Duration.ofMinutes(1)))
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson()
                    .extractingPath("$.errors[*].field")
                    .isEqualTo(List.of("grace_period_seconds"));
        }
        assertThat(access.keys(organization.test()).getFirst().expiresAt()).isEmpty();
    }

    @Test
    void refusesToRollAKeyTwiceOrARevokedKey() {
        String rolled =
                issued(organization, Environment.TEST, "Rolled").id().publicId().toString();
        roll(organization.test(), rolled, null, Duration.ofMinutes(1));
        String revoked = issued(organization, Environment.TEST, "Revoked")
                .id()
                .publicId()
                .toString();
        revoke(organization.test(), revoked, Duration.ofMinutes(1));

        for (String key : List.of(rolled, revoked)) {
            assertThat(roll(organization.test(), key, null, Duration.ofMinutes(1)))
                    .hasStatus(HttpStatus.CONFLICT)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("key_not_rollable");
        }
    }

    @Test
    void findsNoKeyToRollOfAnotherTenantOrWithAnUnknownOrMalformedId() {
        IssuedApiKey live = issued(organization, Environment.LIVE, "Live");
        String unknown = new ApiKeyId(UUID.randomUUID()).publicId().toString();

        for (String id : List.of(live.id().publicId().toString(), unknown, "key_not-an-id")) {
            assertThat(roll(organization.test(), id, null, Duration.ofMinutes(1)))
                    .hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("not_found");
        }
        assertThat(access.keys(organization.live())).hasSize(1);
        assertThat(access.keys(organization.live()).getFirst().expiresAt()).isEmpty();
    }

    @Test
    void refusesToRollAKeyWithScopesTheMemberDoesNotHold() {
        // Issued by an admin; a developer may manage keys, but not issue refunds.
        String key = issued(organization, Environment.TEST, "Refunds", Set.of(Permission.REFUNDS_WRITE))
                .id()
                .publicId()
                .toString();
        owner.sql("UPDATE tenancy.member_roles SET role = 'DEVELOPER' WHERE member_id = ?")
                .param(organization.admin().value())
                .update();

        assertThat(roll(organization.test(), key, null, Duration.ofMinutes(1)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    @Test
    void rollsAtTheLimitOnlyWithoutAGracePeriod() {
        String key =
                issued(organization, Environment.TEST, "Key 0").id().publicId().toString();
        for (int i = 1; i < AccessApi.MAX_ACTIVE_KEYS; i++) {
            issued(organization, Environment.TEST, "Key " + i);
        }

        assertThat(roll(organization.test(), key, null, Duration.ofMinutes(1)))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("limit_exceeded");
        assertThat(roll(organization.test(), key, """
                        {"grace_period_seconds": 0}
                        """, Duration.ofMinutes(1))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesToRollForAMemberWhoCannotManageKeys() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");
        owner.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
                .param(organization.admin().value())
                .update();

        assertThat(roll(organization.test(), key.id().publicId().toString(), null, Duration.ofMinutes(1)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    @Test
    void requiresARecentAuthenticationToRoll() {
        IssuedApiKey key = issued(organization, Environment.TEST, "Checkout");

        assertThat(roll(organization.test(), key.id().publicId().toString(), null, Duration.ofMinutes(6)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("step_up_required");
        assertThat(access.keys(organization.test())).hasSize(1);
        assertThat(access.keys(organization.test()).getFirst().expiresAt()).isEmpty();
    }

    // Found by ID: a replacement issued in the same transaction has the same created_at.
    private Instant expiresAt(IssuedApiKey key) {
        return access.keys(organization.test()).stream()
                .filter(view -> view.id().equals(key.id()))
                .findFirst()
                .orElseThrow()
                .expiresAt()
                .orElseThrow();
    }

    private MvcTestResult roll(TenantId tenant, String keyId, @Nullable String body, Duration sinceAuthentication) {
        var request = mvc.post()
                .uri("/internal/tenants/{tenant_id}/api_keys/{key_id}/roll", tenant.publicId(), keyId)
                .with(jwt().jwt(token -> token.subject(subject)
                        .claim(
                                "auth_time",
                                Instant.now().minus(sinceAuthentication).getEpochSecond())));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcTestResult result = request.exchange();
        OpenApiContract.internal().assertConforms(result);
        return result;
    }

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

    private MvcTestResultAssert list(TenantId tenant) {
        MvcTestResult result = mvc.get()
                .uri("/internal/tenants/{tenant_id}/api_keys", tenant.publicId())
                .with(jwt().jwt(token -> token.subject(subject)))
                .exchange();
        OpenApiContract.internal().assertConforms(result);
        return assertThat(result);
    }

    private IssuedApiKey issued(NewOrganization owner, Environment environment, String name) {
        return issued(owner, environment, name, Set.of(Permission.CHARGES_READ, Permission.CHARGES_WRITE));
    }

    private IssuedApiKey issued(NewOrganization owner, Environment environment, String name, Set<Permission> scopes) {
        return AccessFixtures.issue(access, AccessFixtures.admin(owner, environment), name, scopes);
    }

    private static Object utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
