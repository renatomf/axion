package dev.finaxis.core.access;

import static dev.finaxis.core.tenancy.Permission.CHARGES_READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.TenancyApi;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

/**
 * The access module's statements run as {@code finaxis_access}, so Row-Level Security binds them
 * even though the tests connect as a superuser: with no tenant bound, they reach no key.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AccessModuleRoleTest {

    private final AccessApi access;
    private final TenancyApi tenancy;
    private NewOrganization organization;

    @Autowired
    AccessModuleRoleTest(AccessApi access, TenancyApi tenancy) {
        this.access = access;
        this.tenancy = tenancy;
    }

    @BeforeEach
    void createOrganization() {
        organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
    }

    @Test
    void listsNoKeyWithNoTenantBound() {
        AccessFixtures.issue(access, admin(), "Checkout", Set.of(CHARGES_READ));

        assertThat(access.keys(organization.test())).isEmpty();
    }

    @Test
    void issuesNoKeyWithNoTenantBound() {
        assertThatThrownBy(() -> access.issue(admin(), "Checkout", Set.of(CHARGES_READ)))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("violates row-level security policy");
    }

    private MemberCaller admin() {
        return AccessFixtures.admin(organization, Environment.TEST);
    }
}
