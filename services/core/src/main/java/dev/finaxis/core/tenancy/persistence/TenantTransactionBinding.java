package dev.finaxis.core.tenancy.persistence;

import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Hands the tenant in {@link TenantContext} to Row-Level Security as {@code finaxis.tenant_id}, at
 * the start of every transaction (tenancy design, section 5.3). The setting is transaction-local,
 * so a pooled connection never carries it into the next transaction.
 */
@Component
public class TenantTransactionBinding implements TransactionExecutionListener {

    public static final String TENANT_SETTING = "finaxis.tenant_id";

    private final JdbcClient jdbc;

    TenantTransactionBinding(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure == null) {
            TenantContext.current().map(TenantContext.Binding::tenant).ifPresent(this::bind);
        }
    }

    private void bind(TenantId tenant) {
        jdbc.sql("SELECT set_config(?, ?, true)")
                .param(TENANT_SETTING)
                .param(tenant.value().toString())
                .query(String.class)
                .single();
    }
}
