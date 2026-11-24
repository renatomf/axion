package dev.axion.core.notifications.application;

import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Ends the live streams before the web server's graceful shutdown begins. That shutdown waits for
 * the requests in progress, and an open stream is one until it ends: left open, each would hold the
 * core's stop for the whole grace period.
 */
@Component
class StreamShutdown implements SmartLifecycle {

    private final LiveStreams streams;
    private volatile boolean running;

    StreamShutdown(LiveStreams streams) {
        this.streams = streams;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        streams.closeAll();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // Lifecycles stop from the highest phase down, so this one stops just before the graceful shutdown.
    @Override
    public int getPhase() {
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1;
    }
}
