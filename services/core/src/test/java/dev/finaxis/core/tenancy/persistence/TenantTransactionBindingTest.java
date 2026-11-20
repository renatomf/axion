package dev.finaxis.core.tenancy.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.TenancyApi;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantTransactionBindingTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final TenancyApi tenancy;

    @Autowired
    TenantTransactionBindingTest(TransactionTemplate transactions, JdbcClient jdbc, TenancyApi tenancy) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.tenancy = tenancy;
    }

    @Test
    void bindsTheTenantToTheTransaction() {
        assertThat(TenantContext.call(
                        TENANT, Environment.TEST, () -> inTransaction().tenant()))
                .isEqualTo(TENANT.value().toString());
    }

    @Test
    void bindsNothingWithoutATenant() {
        Observed observed = inTransaction();

        assertThat(observed.tenant()).isEmpty();
        assertThat(observed.platformTenant()).isEmpty();
    }

    @Test
    void bindsThePlatformTenantOfTheTenantsEnvironment() {
        assertThat(TenantContext.call(TENANT, Environment.TEST, this::inTransaction)
                        .platformTenant())
                .isEqualTo(tenancy.platformTenant(Environment.TEST).value().toString());
        assertThat(TenantContext.call(TENANT, Environment.LIVE, this::inTransaction)
                        .platformTenant())
                .isEqualTo(tenancy.platformTenant(Environment.LIVE).value().toString());
    }

    @Test
    void bindsTheTenantToReadOnlyTransactions() {
        assertThat(TenantContext.call(TENANT, Environment.TEST, this::inReadOnlyTransaction))
                .isEqualTo(TENANT.value().toString());
    }

    @Test
    void theNextTransactionOnTheConnectionDoesNotInheritTheTenant() {
        Observed bound = TenantContext.call(TENANT, Environment.TEST, this::inTransaction);
        Observed next = inTransaction();

        assertThat(next.connection()).isEqualTo(bound.connection());
        assertThat(next.tenant()).isEmpty();
        assertThat(next.platformTenant()).isEmpty();
    }

    private Observed inTransaction() {
        return Objects.requireNonNull(transactions.execute(status -> new Observed(
                jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single(),
                setting(TenantTransactionBinding.TENANT_SETTING),
                setting(TenantTransactionBinding.PLATFORM_TENANT_SETTING))));
    }

    private String inReadOnlyTransaction() {
        var readOnly = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        readOnly.setReadOnly(true);
        return Objects.requireNonNull(readOnly.execute(status -> setting(TenantTransactionBinding.TENANT_SETTING)));
    }

    /** Empty when unset: PostgreSQL returns null for a setting never made, an empty string after one ends. */
    private String setting(String name) {
        return jdbc.sql("SELECT current_setting(?, true)")
                .param(name)
                .query(String.class)
                .optional()
                .orElse("");
    }

    /** What a transaction saw: its connection, by server process ID, and its tenant settings. */
    private record Observed(int connection, String tenant, String platformTenant) {}
}
