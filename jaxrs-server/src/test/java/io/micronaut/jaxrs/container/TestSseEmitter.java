package io.micronaut.jaxrs.container;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.sse.Event;
import io.micronaut.http.sse.SseEmitter;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An emitter recording the events sent to it: a send completes once {@link #take()} takes it, like
 * a connection taking the events of a slow client.
 */
final class TestSseEmitter implements SseEmitter {

    final List<Event<?>> sent = new CopyOnWriteArrayList<>();
    final List<CompletableFuture<Void>> pending = new CopyOnWriteArrayList<>();
    private final List<Consumer<@Nullable Throwable>> onClose = new CopyOnWriteArrayList<>();
    volatile boolean keptOpen;
    volatile boolean completed;
    volatile @Nullable Throwable failure;
    private final boolean immediate;

    TestSseEmitter() {
        this(true);
    }

    /**
     * @param immediate Whether a send completes at once, else when taken
     */
    TestSseEmitter(boolean immediate) {
        this.immediate = immediate;
    }

    /**
     * The connection takes the oldest pending event.
     */
    void take() {
        pending.remove(0).complete(null);
    }

    /**
     * The client disconnects: the pending sends fail, and the close callbacks run.
     */
    void disconnect() {
        close(new IOException("The client disconnected"));
    }

    @Override
    public synchronized CompletionStage<Void> send(Event<?> event) {
        if (!isOpen()) {
            return CompletableFuture.failedFuture(new IllegalStateException("closed"));
        }
        sent.add(event);
        if (immediate) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> stage = new CompletableFuture<>();
        pending.add(stage);
        return stage;
    }

    @Override
    public CompletionStage<Void> comment(String comment) {
        return send(Event.of("").comment(comment));
    }

    @Override
    public boolean isWritable() {
        return isOpen();
    }

    @Override
    public boolean isOpen() {
        return !completed && failure == null;
    }

    @Override
    public Optional<String> lastEventId() {
        return Optional.empty();
    }

    @Override
    public SseEmitter header(CharSequence name, CharSequence value) {
        return this;
    }

    @Override
    public void respond(HttpResponse<?> response) {
        complete();
    }

    @Override
    public SseEmitter keepOpen() {
        keptOpen = true;
        return this;
    }

    @Override
    public SseEmitter heartbeat(Duration period) {
        return this;
    }

    @Override
    public SseEmitter highWaterMark(int bytes) {
        return this;
    }

    @Override
    public SseEmitter onClose(Consumer<@Nullable Throwable> callback) {
        onClose.add(callback);
        if (!isOpen()) {
            callback.accept(failure);
        }
        return this;
    }

    @Override
    public void complete() {
        synchronized (this) {
            if (!isOpen()) {
                return;
            }
            completed = true;
        }
        pending.forEach(stage -> stage.complete(null));
        pending.clear();
        onClose.forEach(callback -> callback.accept(null));
    }

    @Override
    public void fail(Throwable cause) {
        close(cause);
    }

    private void close(Throwable cause) {
        synchronized (this) {
            if (!isOpen()) {
                return;
            }
            failure = cause;
        }
        pending.forEach(stage -> stage.completeExceptionally(cause));
        pending.clear();
        onClose.forEach(callback -> callback.accept(cause));
    }
}
