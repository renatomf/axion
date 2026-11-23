package dev.axion.core;

import dev.axion.core.shared.Actor;
import dev.axion.core.shared.ActorContext;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import java.util.UUID;

/**
 * Binds a tenant and a member of the tests, as a request binds its caller, for tests that call a
 * module's API directly: every change publishes an event, which names its actor.
 */
public final class TestCaller {

    public static final Actor ACTOR =
            new Actor.Member(new MemberId(UUID.fromString("0193a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b")));

    public static final String CORRELATION_ID = "test-request";

    private TestCaller() {}

    public static void run(TenantId tenant, Environment environment, Runnable work) {
        TenantContext.run(tenant, environment, () -> ActorContext.run(ACTOR, CORRELATION_ID, work));
    }

    public static <T, X extends Throwable> T call(
            TenantId tenant, Environment environment, ScopedValue.CallableOp<T, X> work) throws X {
        return TenantContext.call(tenant, environment, () -> ActorContext.call(ACTOR, CORRELATION_ID, work));
    }
}
