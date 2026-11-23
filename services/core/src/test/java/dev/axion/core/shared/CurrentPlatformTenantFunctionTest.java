package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.tenancy.TenancyApi;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class CurrentPlatformTenantFunctionTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final TenancyApi tenancy;

    @Autowired
    CurrentPlatformTenantFunctionTest(TransactionTemplate transactions, JdbcClient jdbc, TenancyApi tenancy) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.tenancy = tenancy;
    }

    @Test
    void returnsThePlatformTenantOfTheBoundEnvironment() {
        assertThat(TenantContext.call(TENANT, Environment.TEST, this::currentPlatformTenant))
                .contains(tenancy.platformTenant(Environment.TEST).value());
    }

    @Test
    void returnsNullWithoutATenant() {
        assertThat(currentPlatformTenant()).isEmpty();
    }

    private Optional<UUID> currentPlatformTenant() {
        return Objects.requireNonNull(transactions.execute(status -> {
            DatabaseRole.LEDGER.actAs(jdbc);
            return jdbc.sql("SELECT shared.current_platform_tenant()")
                    .query(UUID.class)
                    .optional();
        }));
    }
}
