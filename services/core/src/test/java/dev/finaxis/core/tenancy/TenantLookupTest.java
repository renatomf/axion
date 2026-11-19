package dev.finaxis.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

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
    private final JdbcClient jdbc;

    @Autowired
    TenantLookupTest(TenancyApi tenancy, JdbcClient jdbc) {
        this.tenancy = tenancy;
        this.jdbc = jdbc;
    }

    @ParameterizedTest
    @EnumSource(Environment.class)
    void namesTheActivePlatformTenantOfEachEnvironment(Environment environment) {
        var tenant = tenancy.tenant(tenancy.platformTenant(environment)).orElseThrow();

        assertThat(tenant.environment()).isEqualTo(environment);
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(isPlatform(tenant.organization())).isTrue();
    }

    @Test
    void findsNoTenantForAnUnknownId() {
        assertThat(tenancy.tenant(new TenantId(UUID.randomUUID()))).isEmpty();
    }

    private boolean isPlatform(OrganizationId organization) {
        return jdbc.sql("SELECT is_platform FROM tenancy.organizations WHERE id = ?")
                .param(organization.value())
                .query(Boolean.class)
                .single();
    }
}
