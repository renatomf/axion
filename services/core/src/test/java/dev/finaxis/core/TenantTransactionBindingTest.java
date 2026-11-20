package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
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

    @Autowired
    TenantTransactionBindingTest(TransactionTemplate transactions, JdbcClient jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    @Test
    void bindsTheTenantToTheTransaction() {
        assertThat(TenantContext.call(TENANT, () -> inTransaction().tenant()))
                .isEqualTo(TENANT.value().toString());
    }

    @Test
    void bindsNothingWithoutATenant() {
        assertThat(inTransaction().tenant()).isEmpty();
    }

    @Test
    void bindsTheTenantToReadOnlyTransactions() {
        assertThat(TenantContext.call(TENANT, this::inReadOnlyTransaction))
                .isEqualTo(TENANT.value().toString());
    }

    @Test
    void theNextTransactionOnTheConnectionDoesNotInheritTheTenant() {
        Observed bound = TenantContext.call(TENANT, this::inTransaction);
        Observed next = inTransaction();

        assertThat(next.connection()).isEqualTo(bound.connection());
        assertThat(next.tenant()).isEmpty();
    }

    private Observed inTransaction() {
        return Objects.requireNonNull(transactions.execute(status -> new Observed(
                jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single(), setting())));
    }

    private String inReadOnlyTransaction() {
        var readOnly = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        readOnly.setReadOnly(true);
        return Objects.requireNonNull(readOnly.execute(status -> setting()));
    }

    /** Empty when unset: PostgreSQL returns null for a setting never made, an empty string after one ends. */
    private String setting() {
        return jdbc.sql("SELECT current_setting(?, true)")
                .param(TenantTransactionBinding.TENANT_SETTING)
                .query(String.class)
                .optional()
                .orElse("");
    }

    /** What a transaction saw: its connection, by server process ID, and its tenant setting. */
    private record Observed(int connection, String tenant) {}
}
