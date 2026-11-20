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
        AtomicReference<Optional<TenantContext.Binding>> seen = new AtomicReference<>();

        TenantContext.run(TENANT, Environment.TEST, () -> seen.set(TenantContext.current()));

        assertThat(seen).hasValue(Optional.of(new TenantContext.Binding(TENANT, Environment.TEST)));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void returnsWhatTheWorkReturns() {
        assertThat(TenantContext.call(TENANT, Environment.TEST, TenantContext::current))
                .contains(new TenantContext.Binding(TENANT, Environment.TEST));
    }

    @Test
    void unbindsTheTenantWhenTheWorkFails() {
        assertThatThrownBy(() -> TenantContext.call(TENANT, Environment.TEST, () -> {
                    throw new IOException("failed");
                }))
                .isInstanceOf(IOException.class);

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aNestedBindingLastsOnlyForItsOwnWork() {
        TenantId other = new TenantId(UUID.randomUUID());

        TenantContext.run(TENANT, Environment.TEST, () -> {
            assertThat(TenantContext.call(other, Environment.LIVE, TenantContext::current))
                    .contains(new TenantContext.Binding(other, Environment.LIVE));
            assertThat(TenantContext.current()).contains(new TenantContext.Binding(TENANT, Environment.TEST));
        });
    }
}
