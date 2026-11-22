package dev.finaxis.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link TenancyApi#tenant} and {@link TenancyApi#platformTenant} against a real PostgreSQL. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantLookupTest {

    private final TenancyApi tenancy;
    private final JdbcClient owner;

    @Autowired
    TenantLookupTest(TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @ParameterizedTest
    @EnumSource(Environment.class)
    void namesTheActivePlatformTenantOfEachEnvironment(Environment environment) {
        var tenant = tenancy.tenant(tenancy.platformTenant(environment)).orElseThrow();

        assertThat(tenant.environment()).isEqualTo(environment);
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(isPlatform(tenant.organization())).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Environment.class)
    void namesTheOrganizationOfATenant(Environment environment) {
        var created = tenancy.createOrganization("Padaria São João", "subject-" + UUID.randomUUID());
        var id = environment == Environment.TEST ? created.test() : created.live();

        assertThat(tenancy.tenant(id).orElseThrow().organizationName()).isEqualTo("Padaria São João");
    }

    @Test
    void findsNoTenantForAnUnknownId() {
        assertThat(tenancy.tenant(new TenantId(UUID.randomUUID()))).isEmpty();
    }

    private boolean isPlatform(OrganizationId organization) {
        return owner.sql("SELECT is_platform FROM tenancy.organizations WHERE id = ?")
                .param(organization.value())
                .query(Boolean.class)
                .single();
    }
}
