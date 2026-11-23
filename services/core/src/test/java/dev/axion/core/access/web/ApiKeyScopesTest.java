package dev.axion.core.access.web;

import static dev.axion.core.tenancy.Permission.CHARGES_READ;
import static dev.axion.core.tenancy.Permission.CHARGES_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.access.AccessApi;
import dev.axion.core.access.AccessFixtures;
import dev.axion.core.access.ApiKeyCaller;
import dev.axion.core.access.IssuedApiKey;
import dev.axion.core.access.RequiresPermission;
import dev.axion.core.access.RequiresStepUp;
import dev.axion.core.access.TenantBoundAccess;
import dev.axion.core.shared.Environment;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A {@code /v1} endpoint's {@code @RequiresPermission} against the calling key's scopes. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, ApiKeyScopesTest.ScopedController.class})
@AutoConfigureMockMvc
class ApiKeyScopesTest {

    @RestController
    static class ScopedController {

        /** Answers with the caller it received: the key, its tenant and its scopes. */
        @RequiresPermission(CHARGES_WRITE)
        @GetMapping("/v1/scoped")
        String scoped(ApiKeyCaller caller) {
            return caller.key().publicId() + " " + caller.tenant().publicId() + " " + caller.permissions();
        }

        @RequiresPermission(CHARGES_READ)
        @RequiresStepUp
        @GetMapping("/v1/scoped/sensitive")
        String sensitive() {
            return "reached";
        }
    }

    private static final String INSUFFICIENT_PERMISSION = """
            {"status": 403, "detail": "Access is denied.", "code": "insufficient_permission"}
            """;

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private NewOrganization organization;

    @Autowired
    ApiKeyScopesTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void aKeyWithTheScopeReachesTheEndpointAsItsCaller() {
        IssuedApiKey key = issued(Set.of(CHARGES_WRITE));

        get("/v1/scoped", key)
                .hasStatusOk()
                .bodyText()
                .isEqualTo(key.id().publicId() + " " + organization.test().publicId() + " [CHARGES_WRITE]");
    }

    @Test
    void aKeyWithoutTheScopeIsForbidden() {
        isInsufficientPermission(get("/v1/scoped", issued(Set.of(CHARGES_READ))));
    }

    /** A key cannot authenticate again, so it never satisfies a step-up. */
    @Test
    void aKeyNeverSatisfiesAStepUp() {
        isInsufficientPermission(get("/v1/scoped/sensitive", issued(Set.of(CHARGES_READ))));
    }

    private MvcTestResultAssert get(String path, IssuedApiKey key) {
        return assertThat(mvc.get().uri(path).header("Authorization", "Bearer " + key.secret()));
    }

    private static void isInsufficientPermission(MvcTestResultAssert response) {
        response.hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo(INSUFFICIENT_PERMISSION);
    }

    private IssuedApiKey issued(Set<Permission> scopes) {
        return AccessFixtures.issue(access, AccessFixtures.admin(organization, Environment.TEST), "Checkout", scopes);
    }
}
