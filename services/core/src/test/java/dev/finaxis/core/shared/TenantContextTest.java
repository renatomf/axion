package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    @Test
    void bindsNoTenantByDefault() {
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void bindsTheTenantForTheDurationOfTheWork() {
        AtomicReference<Optional<TenantId>> seen = new AtomicReference<>();

        TenantContext.run(TENANT, () -> seen.set(TenantContext.current()));

        assertThat(seen).hasValue(Optional.of(TENANT));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void returnsWhatTheWorkReturns() {
        assertThat(TenantContext.call(TENANT, TenantContext::current)).contains(TENANT);
    }

    @Test
    void unbindsTheTenantWhenTheWorkFails() {
        assertThatThrownBy(() -> TenantContext.call(TENANT, () -> {
                    throw new IOException("failed");
                }))
                .isInstanceOf(IOException.class);

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aNestedBindingLastsOnlyForItsOwnWork() {
        TenantId other = new TenantId(UUID.randomUUID());

        TenantContext.run(TENANT, () -> {
            assertThat(TenantContext.call(other, TenantContext::current)).contains(other);
            assertThat(TenantContext.current()).contains(TENANT);
        });
    }
}
