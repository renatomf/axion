package dev.finaxis.core.tenancy.application;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.TenancyApi;
import dev.finaxis.core.tenancy.TenantView;
import dev.finaxis.core.tenancy.persistence.JdbcTenants;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenancy module's implementation of {@link TenancyApi}. Other modules depend on the
 * interface; Spring passes them this bean, the only implementation.
 */
@Service
public class TenancyService implements TenancyApi {

    /** The fixed IDs the migration {@code V7__create_platform_organization} gives the platform tenants. */
    private static final TenantId PLATFORM_TEST = new TenantId(UUID.fromString("00000000-0000-7000-8000-000000000002"));

    private static final TenantId PLATFORM_LIVE = new TenantId(UUID.fromString("00000000-0000-7000-8000-000000000003"));

    private final JdbcTenants tenants;

    public TenancyService(JdbcTenants tenants) {
        this.tenants = tenants;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code readOnly = true} tells Spring, and through the driver PostgreSQL, that the call
     * writes nothing; a write by mistake fails instead of committing.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<TenantView> tenant(TenantId tenant) {
        return tenants.find(tenant);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The IDs are fixed by a migration, so no query is needed; a test checks that they name the
     * platform's tenants.
     */
    @Override
    public TenantId platformTenant(Environment environment) {
        return switch (environment) {
            case TEST -> PLATFORM_TEST;
            case LIVE -> PLATFORM_LIVE;
        };
    }
}
