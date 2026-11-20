package dev.finaxis.core.access.web;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.NoPermissionRequired;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The tenant a {@code /v1} endpoint runs with, for Row-Level Security; members are in {@link TenantResolutionTest}. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TenantBindingTest.BoundController.class})
@AutoConfigureMockMvc
class TenantBindingTest {

    @RestController
    static class BoundController {

        @RequiresPermission(CHARGES_READ)
        @GetMapping("/v1/bound")
        String bound() {
            return describe();
        }

        @NoPermissionRequired(reason = "Checks that an endpoint without a caller runs with no tenant")
        @GetMapping("/v1/bound/no-caller")
        String noCaller() {
            return describe();
        }

        private static String describe() {
            return TenantContext.current()
                    .map(tenant -> tenant.publicId().toString())
                    .orElse("none");
        }
    }

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;

    @Autowired
    TenantBindingTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy) {
        this.mvc = mvc;
        this.access = access;
        this.tenancy = tenancy;
    }

    @Test
    void bindsTheKeysTenant() {
        NewOrganization organization =
                tenancy.createOrganization("Acme", UUID.randomUUID().toString());
        IssuedApiKey key = issued(organization);

        get("/v1/bound", key)
                .hasStatusOk()
                .hasBodyTextEqualTo(organization.test().publicId().toString());
    }

    @Test
    void bindsNoTenantWithoutACaller() {
        IssuedApiKey key =
                issued(tenancy.createOrganization("Acme", UUID.randomUUID().toString()));

        get("/v1/bound/no-caller", key).hasStatusOk().hasBodyTextEqualTo("none");
    }

    private MvcTestResultAssert get(String path, IssuedApiKey key) {
        return assertThat(mvc.get().uri(path).header("Authorization", "Bearer " + key.secret()));
    }

    private IssuedApiKey issued(NewOrganization organization) {
        return AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.TEST), "Checkout", Set.of(CHARGES_READ));
    }
}
