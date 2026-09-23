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
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEventSink;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The sink of the events a resource method sends (JAX-RS 9.3): the stream of the
 * {@code text/event-stream} response of the request, see {@link JaxRsServerFilters}. The events
 * sent before the response is written are buffered.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsSseEventSink implements SseEventSink {

    private final Sinks.Many<Event<String>> events = Sinks.many().unicast().onBackpressureBuffer();
    private final Function<OutboundSseEvent, String> dataWriter;
    private final List<Runnable> onClose = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    /**
     * @param dataWriter Writes the data of an event as text, with the message body writers
     */
    JaxRsSseEventSink(Function<OutboundSseEvent, String> dataWriter) {
        this.dataWriter = dataWriter;
    }

    /**
     * @return The events, the body of the response
     */
    Publisher<Event<String>> publisher() {
        // the client disconnected
        return events.asFlux().doOnCancel(this::closed);
    }

    /**
     * @param callback Called once the sink is closed, by the application or by the client
     */
    void onClose(Runnable callback) {
        onClose.add(callback);
        if (closed) {
            callback.run();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized CompletionStage<?> send(OutboundSseEvent event) {
        if (closed) {
            throw new IllegalStateException("The event sink is closed");
        }
        try {
            String data = event.getData() == null ? "" : dataWriter.apply(event);
            Event<String> sent = Event.of(data)
                .id(event.getId())
                .name(event.getName())
                .comment(event.getComment());
            if (event.isReconnectDelaySet()) {
                sent = sent.retry(Duration.ofMillis(event.getReconnectDelay()));
            }
            Sinks.EmitResult result = events.tryEmitNext(sent);
            if (result.isFailure()) {
                return CompletableFuture.failedFuture(new IllegalStateException("The event was not sent: " + result));
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            events.tryEmitComplete();
            closed();
        }
    }

    private void closed() {
        if (!closed) {
            closed = true;
            for (Runnable callback : onClose) {
                callback.run();
            }
        }
    }

    @Override
    public String toString() {
        return "JaxRsSseEventSink[closed=" + closed + "]";
    }
}
