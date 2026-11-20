package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
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
class CurrentTenantFunctionTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    @Autowired
    CurrentTenantFunctionTest(TransactionTemplate transactions, JdbcClient jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    @Test
    void returnsTheTenantBoundToTheTransaction() {
        assertThat(TenantContext.call(TENANT, Environment.TEST, this::currentTenant))
                .contains(TENANT.value());
    }

    @Test
    void returnsNullWithoutATenant() {
        assertThat(currentTenant()).isEmpty();
    }

    /** What PostgreSQL returns once a transaction-local setting has ended on the connection. */
    @Test
    void returnsNullForAnEmptySetting() {
        assertThat(currentTenantWithSetting("")).isEmpty();
    }

    private Optional<UUID> currentTenant() {
        return Objects.requireNonNull(transactions.execute(status -> {
            DatabaseRole.ACCESS.actAs(jdbc);
            return jdbc.sql("SELECT shared.current_tenant()").query(UUID.class).optional();
        }));
    }

    private Optional<UUID> currentTenantWithSetting(String setting) {
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.sql("SELECT set_config('finaxis.tenant_id', ?, true)")
                    .param(setting)
                    .query()
                    .singleRow();
            return currentTenant();
        }));
    }
}
