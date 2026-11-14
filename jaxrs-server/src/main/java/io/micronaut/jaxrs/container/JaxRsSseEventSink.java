/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.sse.Event;
import io.micronaut.http.sse.SseEmitter;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEventSink;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The sink of the events a resource method sends (JAX-RS 9.3): an adapter over the
 * {@link SseEmitter} of the {@code text/event-stream} route of the resource method. The emitter
 * sends the response on the first event, buffers the events of a slow client and completes the
 * stage of a send once the connection takes it.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsSseEventSink implements SseEventSink {

    private final SseEmitter events;
    private final Function<OutboundSseEvent, String> dataWriter;
    private final Executor callbackExecutor;
    private final List<Runnable> onClose = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param events     The emitter of the events of the response
     * @param dataWriter Writes the data of an event as text, with the message body writers
     */
    JaxRsSseEventSink(SseEmitter events, Function<OutboundSseEvent, String> dataWriter) {
        this(events, dataWriter, Runnable::run);
    }

    /**
     * @param events           The emitter of the events of the response, kept open until the sink is closed
     * @param dataWriter       Writes the data of an event as text, with the message body writers
     * @param callbackExecutor The executor of the close callbacks when the client disconnects,
     *                         e.g. the blocking executor: not the event loop reporting it, the
     *                         callbacks of the application can block
     */
    JaxRsSseEventSink(SseEmitter events, Function<OutboundSseEvent, String> dataWriter, Executor callbackExecutor) {
        this.events = events;
        this.dataWriter = dataWriter;
        this.callbackExecutor = callbackExecutor;
        // the resource method may send its events after it returns, until it closes the sink
        events.keepOpen();
        events.onClose(failure -> disconnected());
    }

    /**
     * The emitter of the events of the response.
     *
     * @return The emitter
     */
    SseEmitter events() {
        return events;
    }

    /**
     * The client disconnected, or the stream ended otherwise: the close callbacks run on the
     * callback executor.
     */
    void disconnected() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            callbackExecutor.execute(this::runCallbacks);
        } catch (RejectedExecutionException e) {
            // the executor is shut down: run on this thread
            runCallbacks();
        }
        // pending sends fail, if the emitter did not notice it yet, e.g. before the response
        events.fail(new IOException("The client disconnected"));
    }

    /**
     * @param callback Called once the sink is closed, by the application or by the client
     * @return Removes the callback, e.g. of a broadcaster closed without closing the sink
     */
    Runnable onClose(Runnable callback) {
        // called once, also when the sink closes while the callback is added
        AtomicBoolean called = new AtomicBoolean();
        Runnable once = () -> {
            if (called.compareAndSet(false, true)) {
                callback.run();
            }
        };
        onClose.add(once);
        if (closed.get()) {
            once.run();
        }
        return () -> onClose.remove(once);
    }

    @Override
    public boolean isClosed() {
        return closed.get() || !events.isOpen();
    }

    @Override
    public CompletionStage<?> send(OutboundSseEvent event) {
        if (isClosed()) {
            throw new IllegalStateException("The event sink is closed");
        }
        try {
            String data = event.getData() == null ? "" : dataWriter.apply(event);
            Event<String> sent = Event.of(data)
                .id(singleLine(event.getId(), "id"))
                .name(singleLine(event.getName(), "name"))
                .comment(event.getComment());
            if (event.isReconnectDelaySet()) {
                sent = sent.retry(Duration.ofMillis(event.getReconnectDelay()));
            }
            return events.send(sent);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static @Nullable String singleLine(@Nullable String value, String field) {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException("The " + field + " of an event cannot contain a line break");
        }
        return value;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            events.complete();
            runCallbacks();
        }
    }

    private void runCallbacks() {
        for (Runnable callback : onClose) {
            callback.run();
        }
    }

    @Override
    public String toString() {
        return "JaxRsSseEventSink[closed=" + isClosed() + "]";
    }
}
