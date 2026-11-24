package dev.axion.core.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.MutableClock;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StreamMetricsTest {

    private final LiveStreams streams = new LiveStreams(new MutableClock(Instant.parse("2026-11-24T12:00:00Z")));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void countsTheStreamsOpen() {
        new StreamMetrics(streams, meters);
        StreamMember first = member();
        LiveStreamsTest.TestSink closing = new LiveStreamsTest.TestSink(1);
        streams.open(first, closing, Optional.empty());
        streams.open(member(), new LiveStreamsTest.TestSink(1), Optional.empty());

        assertThat(gauge()).isEqualTo(2);

        streams.close(first, closing);
        assertThat(gauge()).isEqualTo(1);
    }

    private double gauge() {
        return meters.get(StreamMetrics.STREAMS).gauge().value();
    }

    private static StreamMember member() {
        return new StreamMember(
                new TenantId(UUID.randomUUID()), new MemberId(UUID.randomUUID()), Set.of(Permission.CHARGES_READ));
    }
}
