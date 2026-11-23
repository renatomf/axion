package dev.axion.core.shared;

import java.util.Optional;

/**
 * The tenant the current work acts for, which Row-Level Security reads through the transaction
 * (tenancy design, section 5.3). It is bound for the duration of a call and cannot outlive it.
 */
public final class TenantContext {

    /** A tenant and its environment, which selects the platform tenant beside it (section 5.5). */
    public record Binding(TenantId tenant, Environment environment) {}

    private static final ScopedValue<Binding> BINDING = ScopedValue.newInstance();

    private TenantContext() {}

    /** The bound tenant; empty outside {@link #run} and {@link #call}. */
    public static Optional<Binding> current() {
        return BINDING.isBound() ? Optional.of(BINDING.get()) : Optional.empty();
    }

    public static void run(TenantId tenant, Environment environment, Runnable work) {
        ScopedValue.where(BINDING, new Binding(tenant, environment)).run(work);
    }

    public static <T, X extends Throwable> T call(
            TenantId tenant, Environment environment, ScopedValue.CallableOp<T, X> work) throws X {
        return ScopedValue.where(BINDING, new Binding(tenant, environment)).call(work);
    }
}
