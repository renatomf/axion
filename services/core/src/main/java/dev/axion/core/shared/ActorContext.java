package dev.axion.core.shared;

import java.util.Optional;

/**
 * Who the current work acts as and the correlation ID it runs under, which the events it publishes
 * record (events and webhooks design, section 4.1). A request binds its caller; a job binds itself.
 * Like {@link TenantContext}, it is bound for the duration of a call and cannot outlive it.
 */
public final class ActorContext {

    /** An actor and the ID that ties its work to the request or job run that did it. */
    public record Binding(Actor actor, String correlationId) {

        public Binding {
            if (correlationId.isBlank()) {
                throw new IllegalArgumentException("Work is bound with the ID of its request or job run");
            }
        }
    }

    private static final ScopedValue<Binding> BINDING = ScopedValue.newInstance();

    private ActorContext() {}

    /** The bound actor; empty outside {@link #run} and {@link #call}. */
    public static Optional<Binding> current() {
        return BINDING.isBound() ? Optional.of(BINDING.get()) : Optional.empty();
    }

    public static void run(Actor actor, String correlationId, Runnable work) {
        ScopedValue.where(BINDING, new Binding(actor, correlationId)).run(work);
    }

    public static <T, X extends Throwable> T call(Actor actor, String correlationId, ScopedValue.CallableOp<T, X> work)
            throws X {
        return ScopedValue.where(BINDING, new Binding(actor, correlationId)).call(work);
    }
}
