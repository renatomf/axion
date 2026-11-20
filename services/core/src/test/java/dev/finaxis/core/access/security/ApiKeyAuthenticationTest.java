package dev.finaxis.core.access.security;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.NoPermissionRequired;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.access.security.AuthenticationFailures.Failure;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** API key authentication on {@code /v1}. Keys are issued at run time, so none is in the repository. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, ApiKeyAuthenticationTest.PublicApiController.class})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyAuthenticationTest {

    @RestController
    static class PublicApiController {

        /** Answers with the caller's name, the key's public ID, to show who authenticated. */
        @NoPermissionRequired(reason = "Stands in for a /v1 endpoint in this test only")
        @GetMapping("/v1/probe")
        String probe() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication == null ? "nobody" : authentication.getName();
        }
    }

    private static final String UNAUTHORIZED = """
            {"status": 401, "detail": "Authentication is required.", "code": "unauthorized"}
            """;

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;
    private final MeterRegistry meters;
    private NewOrganization organization;

    @Autowired
    ApiKeyAuthenticationTest(
            MockMvcTester mvc,
            AccessApi access,
            TenancyApi tenancy,
            DatabaseOwner databaseOwner,
            MeterRegistry meters) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access);
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
        this.meters = meters;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void aWorkingKeyAuthenticatesTheRequest() {
        IssuedApiKey key = issued();

        probeWith("Bearer " + key.secret())
                .hasStatusOk()
                .bodyText()
                .isEqualTo(key.id().publicId().toString());
    }

    @Test
    void aRequestWithoutCredentialIsUnauthorized() {
        isUnauthorized(assertThat(mvc.get().uri("/v1/probe")));
    }

    /** Every reason gets the same answer, so a caller cannot tell them apart. */
    @Test
    void everyRejectedKeyGetsTheSameAnswer() {
        IssuedApiKey revoked = issued();
        access.revoke(admin(), revoked.id());
        IssuedApiKey expired = issued();
        access.roll(admin(), expired.id(), Duration.ZERO);
        String neverIssued =
                ApiKeySecret.generate(Environment.TEST, new SecureRandom()).reveal();

        isUnauthorized(probeWith("Bearer " + revoked.secret()));
        isUnauthorized(probeWith("Bearer " + expired.secret()));
        isUnauthorized(probeWith("Bearer " + neverIssued));
        isUnauthorized(probeWith("Bearer sk_test_not-a-real-key"));
    }

    /** The counters are shared by the whole test run, so the test compares before and after. */
    @Test
    void everyRejectionIsCountedWithItsReason() {
        IssuedApiKey revoked = issued();
        access.revoke(admin(), revoked.id());
        IssuedApiKey expired = issued();
        access.roll(admin(), expired.id(), Duration.ZERO);
        String neverIssued =
                ApiKeySecret.generate(Environment.TEST, new SecureRandom()).reveal();
        Map<Failure, Double> before = failureCounts();

        probeWith("Bearer " + revoked.secret());
        probeWith("Bearer " + expired.secret());
        probeWith("Bearer " + neverIssued);
        probeWith("Bearer sk_test_not-a-real-key");
        probeWith("Bearer a, Bearer b");
        assertThat(mvc.get().uri("/v1/probe")).hasStatus(HttpStatus.UNAUTHORIZED);
        probeWith("Basic " + revoked.secret());

        assertThat(increaseSince(before))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        Failure.MISSING, 2.0,
                        Failure.MALFORMED, 2.0,
                        Failure.UNKNOWN, 1.0,
                        Failure.REVOKED, 1.0,
                        Failure.EXPIRED, 1.0));
    }

    @Test
    void aRejectionIsLoggedWithTheKeyButNotTheSecret(CapturedOutput output) {
        IssuedApiKey revoked = issued();
        access.revoke(admin(), revoked.id());

        probeWith("Bearer " + revoked.secret());

        String line = output.getOut()
                .lines()
                .filter(l -> l.contains("API key authentication failed"))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(JsonPath.<String>read(line, "$.reason")).isEqualTo("revoked");
        assertThat(JsonPath.<String>read(line, "$.api_key_id"))
                .isEqualTo(revoked.id().publicId().toString());
        assertThat(output.getOut()).doesNotContain(revoked.secret());
    }

    /** A key outside a single {@code Bearer} credential is not read. */
    @Test
    void aKeyOutsideABearerCredentialIsUnauthorized() {
        IssuedApiKey key = issued();

        isUnauthorized(probeWith("Basic " + key.secret()));
        isUnauthorized(probeWith(key.secret()));
        isUnauthorized(assertThat(mvc.get().uri("/v1/probe").queryParam("access_token", key.secret())));
    }

    @Test
    void aWorkingKeyOfAnInactiveTenantIsForbidden() {
        IssuedApiKey key = issued();
        owner.sql("UPDATE tenancy.tenants SET status = 'SUSPENDED' WHERE id = ?")
                .param(organization.test().value())
                .update();

        probeWith("Bearer " + key.secret())
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status": 403, "detail": "The tenant is not active.", "code": "tenant_inactive"}
                        """);
    }

    /** An unknown path is a {@code 404} only for an authenticated caller, as on {@code /internal}. */
    @Test
    void anUnknownPathAnswersOnlyAnAuthenticatedCaller() {
        isUnauthorized(assertThat(mvc.get().uri("/v1/unknown")));
        assertThat(mvc.get().uri("/v1/unknown").header("Authorization", "Bearer " + issued().secret()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private Map<Failure, Double> failureCounts() {
        Map<Failure, Double> counts = new EnumMap<>(Failure.class);
        for (Failure failure : Failure.values()) {
            counts.put(
                    failure,
                    meters.get(AuthenticationFailures.FAILURES)
                            .tag("reason", failure.tag())
                            .counter()
                            .count());
        }
        return counts;
    }

    private Map<Failure, Double> increaseSince(Map<Failure, Double> before) {
        Map<Failure, Double> increase = failureCounts();
        increase.replaceAll((failure, now) -> now - before.getOrDefault(failure, 0.0));
        return increase;
    }

    private MvcTestResultAssert probeWith(String authorization) {
        return assertThat(mvc.get().uri("/v1/probe").header("Authorization", authorization));
    }

    private static void isUnauthorized(MvcTestResultAssert response) {
        response.hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo(UNAUTHORIZED);
    }

    private MemberCaller admin() {
        return AccessFixtures.admin(organization, Environment.TEST);
    }

    private IssuedApiKey issued() {
        return AccessFixtures.issue(access, admin(), "Checkout", Set.of(CHARGES_READ));
    }
}
