package dev.finaxis.core.shared;

import java.util.Optional;

/**
 * The tenant the current work acts for, which Row-Level Security reads through the transaction
 * (tenancy design, section 5.3). It is bound for the duration of a call and cannot outlive it.
 */
public final class TenantContext {

    private static final ScopedValue<TenantId> TENANT = ScopedValue.newInstance();

    private TenantContext() {}

    /** The bound tenant; empty outside {@link #run} and {@link #call}. */
    public static Optional<TenantId> current() {
        return TENANT.isBound() ? Optional.of(TENANT.get()) : Optional.empty();
    }

    public static void run(TenantId tenant, Runnable work) {
        ScopedValue.where(TENANT, tenant).run(work);
    }

    public static <T, X extends Throwable> T call(TenantId tenant, ScopedValue.CallableOp<T, X> work) throws X {
        return ScopedValue.where(TENANT, tenant).call(work);
    }
}
