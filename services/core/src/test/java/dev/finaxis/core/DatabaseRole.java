package dev.finaxis.core;

import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.persistence.TenantTransactionBinding;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The modules' database roles, for tests that prove the code needs no privilege beyond a role's grants. */
public enum DatabaseRole {
    ACCESS,
    TENANCY,
    LEDGER,
    LEDGER_JOBS,
    IDEMPOTENCY,
    IDEMPOTENCY_JOBS,
    BILLING,
    BILLING_JOBS,
    RISK,
    RISK_JOBS,
    RAILS;

    /** Acts as this role until the current transaction ends. */
    public void actAs(JdbcClient jdbc) {
        jdbc.sql("SET LOCAL ROLE finaxis_" + name().toLowerCase(Locale.ROOT)).update();
    }

    /**
     * Acts as this role for the tenant until the current transaction ends, as a request's
     * transaction does once the tenant context binds it.
     */
    public void actAs(JdbcClient jdbc, TenantId tenant) {
        actAs(jdbc);
        jdbc.sql("SELECT set_config(?, ?, true)")
                .param(TenantTransactionBinding.TENANT_SETTING)
                .param(tenant.value().toString())
                .query()
                .singleRow();
    }

    /** As {@link #actAs(JdbcClient, TenantId)}, with the platform tenant of the tenant's environment. */
    public void actAs(JdbcClient jdbc, TenantId tenant, TenantId platform) {
        actAs(jdbc, tenant);
        jdbc.sql("SELECT set_config(?, ?, true)")
                .param(TenantTransactionBinding.PLATFORM_TENANT_SETTING)
                .param(platform.value().toString())
                .query()
                .singleRow();
    }
}
