package dev.axion.core.notifications.web;

import dev.axion.core.notifications.application.StreamMessage;
import dev.axion.core.notifications.application.StreamSink;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One client's connection: a queue of {@value #CAPACITY} messages, written to the client by a virtual
 * thread of its own (events and webhooks design, section 10). Whoever offers a message never waits
 * on the client's socket.
 */
final class SseStream implements StreamSink {

    static final int CAPACITY = 256;

    private final SseEmitter emitter;
    private final Consumer<SseStream> onEnd;
    private final BlockingQueue<StreamMessage> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private @Nullable Thread writer;

    /**
     * @param onEnd run once, when the connection ends for any reason
     */
    SseStream(SseEmitter emitter, Consumer<SseStream> onEnd) {
        this.emitter = emitter;
        this.onEnd = onEnd;
        emitter.onCompletion(this::close);
        emitter.onTimeout(this::close);
        emitter.onError(failure -> close());
    }

    void start() {
        writer = Thread.ofVirtual().name("live-stream").start(this::write);
    }

    @Override
    public boolean offer(StreamMessage message) {
        return !closed.get() && queue.offer(message);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (writer != null) {
                writer.interrupt();
            }
            emitter.complete();
            onEnd.accept(this);
        }
    }

    private void write() {
        try {
            while (!closed.get()) {
                emitter.send(event(queue.take()));
            }
        } catch (InterruptedException closing) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException gone) {
            // The client went away, or the emitter completed while a message was on its way.
            close();
        }
    }

    private static SseEmitter.SseEventBuilder event(StreamMessage message) {
        return switch (message) {
            case StreamMessage.Event(var event) ->
                SseEmitter.event()
                        .id(event.id().publicId().toString())
                        .name(event.type())
                        .data(event.body());
            // With data, or a browser's EventSource would not dispatch it; without an ID, so the
            // client's last event stays the last one it received.
            case StreamMessage.Reset() -> SseEmitter.event().name("reset").data("{}");
            // A comment line, which clients ignore.
            case StreamMessage.Heartbeat() -> SseEmitter.event().comment("");
        };
    }
}
