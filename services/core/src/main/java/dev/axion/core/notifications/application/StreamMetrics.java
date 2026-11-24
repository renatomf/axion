package dev.axion.core.notifications.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** The live streams open on this instance (events and webhooks design, section 12.3). */
@Component
class StreamMetrics {

    static final String STREAMS = "axion.notifications.streams";

    StreamMetrics(LiveStreams streams, MeterRegistry meters) {
        Gauge.builder(STREAMS, streams, LiveStreams::openStreams)
                .description("Live streams open on this instance")
                .register(meters);
    }
}
