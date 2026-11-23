package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActorContextTest {

    private static final Actor JOB = new Actor.Job("charge-expiry");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    @Test
    void bindsNoActorByDefault() {
        assertThat(ActorContext.current()).isEmpty();
    }

    @Test
    void bindsTheActorForTheDurationOfTheWork() {
        assertThat(ActorContext.call(JOB, "run-1", ActorContext::current))
                .contains(new ActorContext.Binding(JOB, "run-1"));
        assertThat(ActorContext.current()).isEmpty();
    }

    @Test
    void unbindsTheActorWhenTheWorkFails() {
        assertThatThrownBy(() -> ActorContext.call(JOB, "run-1", () -> {
                    throw new IOException("failed");
                }))
                .isInstanceOf(IOException.class);

        assertThat(ActorContext.current()).isEmpty();
    }

    @Test
    void refusesABlankCorrelationId() {
        assertThatThrownBy(() -> ActorContext.run(JOB, " ", () -> {})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void headsAnEventWithTheBoundTenantAndActor() {
        var id = new EventId(UUID.randomUUID());
        var now = Instant.parse("2026-11-23T14:03:11.482Z");

        EventHeader header = TenantContext.call(
                TENANT,
                Environment.LIVE,
                () -> ActorContext.call(JOB, "run-1", () -> EventHeader.ofCurrentWork(id, now)));

        assertThat(header).isEqualTo(new EventHeader(id, TENANT, Environment.LIVE, JOB, "run-1", now));
    }

    @Test
    void headsNoEventWithoutATenantOrAnActor() {
        var id = new EventId(UUID.randomUUID());
        var now = Instant.now();

        assertThatThrownBy(() -> ActorContext.run(JOB, "run-1", () -> EventHeader.ofCurrentWork(id, now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant");
        assertThatThrownBy(() -> TenantContext.run(TENANT, Environment.TEST, () -> EventHeader.ofCurrentWork(id, now)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actor");
    }
}
