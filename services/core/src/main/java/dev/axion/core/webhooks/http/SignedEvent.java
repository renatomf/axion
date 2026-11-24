package dev.axion.core.webhooks.http;

/**
 * What one request of a delivery carries (events and webhooks design, sections 8.3 and 8.4): the
 * event's body as stored, and the headers that let the receiver verify it. A class, not a record,
 * so the body it holds is a copy no caller can change after it was signed.
 */
public final class SignedEvent {

    private final String id;
    private final long timestamp;
    private final String signature;
    private final byte[] body;

    /**
     * @param id the event's public ID, sent as {@code webhook-id}, which receivers deduplicate by
     * @param timestamp the attempt's time in Unix seconds, sent as {@code webhook-timestamp}
     * @param signature the {@code webhook-signature} header, over the ID, the timestamp and the body
     */
    public SignedEvent(String id, long timestamp, String signature, byte[] body) {
        this.id = id;
        this.timestamp = timestamp;
        this.signature = signature;
        this.body = body.clone();
    }

    public String id() {
        return id;
    }

    public long timestamp() {
        return timestamp;
    }

    public String signature() {
        return signature;
    }

    public byte[] body() {
        return body.clone();
    }
}
