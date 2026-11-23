package dev.axion.core.tenancy.persistence;

import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.TenancyApi;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Hands the tenant in {@link TenantContext} to Row-Level Security as {@code axion.tenant_id}, and
 * the platform tenant of its environment as {@code axion.platform_tenant_id}, at the start of
 * every transaction (tenancy design, sections 5.3 and 5.5). The settings are transaction-local, so
 * a pooled connection never carries them into the next transaction.
 */
@Component
public class TenantTransactionBinding implements TransactionExecutionListener {

    public static final String TENANT_SETTING = "axion.tenant_id";

    public static final String PLATFORM_TENANT_SETTING = "axion.platform_tenant_id";

    private final JdbcClient jdbc;
    private final TenancyApi tenancy;

    TenantTransactionBinding(JdbcClient jdbc, TenancyApi tenancy) {
        this.jdbc = jdbc;
        this.tenancy = tenancy;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure == null) {
            TenantContext.current().ifPresent(this::bind);
        }
    }

    private void bind(TenantContext.Binding binding) {
        TenantId platform = tenancy.platformTenant(binding.environment());
        jdbc.sql("SELECT set_config(?, ?, true), set_config(?, ?, true)")
                .param(TENANT_SETTING)
                .param(binding.tenant().value().toString())
                .param(PLATFORM_TENANT_SETTING)
                .param(platform.value().toString())
                .query()
                .singleRow();
    }
}
