package dev.axion.core.notifications.application;

/** Where a stream's messages go: the connection to one client. */
public interface StreamSink {

    /**
     * Queues the message for the client without waiting on it; false when the queue is full, as it
     * is for a client that stopped reading.
     */
    boolean offer(StreamMessage message);

    /** Ends the connection; calling it again does nothing. */
    void close();
}
