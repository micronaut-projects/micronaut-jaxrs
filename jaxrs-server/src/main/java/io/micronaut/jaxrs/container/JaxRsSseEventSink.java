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
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
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
final class JaxRsSseEventSink implements SseEventSink, Publisher<Event<String>>, Subscription {

    private final Function<OutboundSseEvent, String> dataWriter;
    private final List<Runnable> onClose = new CopyOnWriteArrayList<>();
    private final Deque<Event<String>> buffer = new ArrayDeque<>();
    private volatile boolean closed;
    // the state of the publisher of the events, guarded by this
    private @Nullable Subscriber<? super Event<String>> subscriber;
    private long demand;
    private boolean completed;
    private boolean completeSent;
    private boolean cancelled;
    private boolean draining;
    private boolean missed;

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
        return this;
    }

    @Override
    public void subscribe(Subscriber<? super Event<String>> s) {
        synchronized (this) {
            if (subscriber == null) {
                subscriber = s;
            } else {
                s.onSubscribe(this);
                s.onError(new IllegalStateException("The events of a sink have one subscriber"));
                return;
            }
        }
        s.onSubscribe(this);
        drain();
    }

    @Override
    public void request(long n) {
        if (n <= 0) {
            cancel();
            return;
        }
        synchronized (this) {
            demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
        }
        drain();
    }

    @Override
    public void cancel() {
        synchronized (this) {
            cancelled = true;
            buffer.clear();
        }
        // the client disconnected
        closed();
    }

    /**
     * Deliver the buffered events the subscriber requested, then the completion: one thread at a
     * time, and without recursion when the subscriber requests more while it receives an event.
     */
    private void drain() {
        synchronized (this) {
            if (draining) {
                missed = true;
                return;
            }
            draining = true;
        }
        while (true) {
            Subscriber<? super Event<String>> s;
            Event<String> next = null;
            boolean complete = false;
            synchronized (this) {
                s = subscriber;
                if (s == null || cancelled) {
                    draining = false;
                    return;
                }
                if (demand > 0 && !buffer.isEmpty()) {
                    next = buffer.poll();
                    demand--;
                } else if (buffer.isEmpty() && completed && !completeSent) {
                    completeSent = true;
                    complete = true;
                } else if (missed) {
                    missed = false;
                    continue;
                } else {
                    draining = false;
                    return;
                }
            }
            if (next != null) {
                s.onNext(next);
            } else if (complete) {
                s.onComplete();
            }
        }
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
            synchronized (this) {
                if (!cancelled) {
                    buffer.add(sent);
                }
            }
            drain();
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            synchronized (this) {
                completed = true;
            }
            drain();
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
