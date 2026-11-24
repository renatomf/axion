package dev.axion.core.notifications.application;

import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends a heartbeat on every live stream every 15 seconds (events and webhooks design, section 10),
 * so proxies do not close a stream on which nothing happens.
 */
@Component
class StreamHeartbeat {

    private final LiveStreams streams;

    StreamHeartbeat(LiveStreams streams) {
        this.streams = streams;
    }

    @Scheduled(initialDelay = 15, fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    void beat() {
        streams.heartbeat();
    }
}
