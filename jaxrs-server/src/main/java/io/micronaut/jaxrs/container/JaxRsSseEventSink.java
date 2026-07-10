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
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.http.sse.Event;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsIOException;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEventSink;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Request-bound {@link SseEventSink} backed by a reactive SSE event stream.
 */
@Internal
final class JaxRsSseEventSink implements SseEventSink, Publisher<Event<String>> {
    static final String ATTRIBUTE = JaxRsSseEventSink.class.getName();

    private final JaxRsContainerMessageBodyHandlerRegistry jaxRsHandlerRegistry;
    private final MessageBodyHandlerRegistry bodyHandlerRegistry;
    private final List<Event<String>> events = new ArrayList<>();
    private final List<SinkSubscription> subscriptions = new ArrayList<>();
    private final List<Consumer<SseEventSink>> closeListeners = new ArrayList<>();
    private boolean closed;

    JaxRsSseEventSink(JaxRsContainerMessageBodyHandlerRegistry jaxRsHandlerRegistry,
                      MessageBodyHandlerRegistry bodyHandlerRegistry) {
        this.jaxRsHandlerRegistry = jaxRsHandlerRegistry;
        this.bodyHandlerRegistry = bodyHandlerRegistry;
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public CompletionStage<?> send(OutboundSseEvent event) {
        Event<String> micronautEvent = toMicronautEvent(event);
        List<SinkSubscription> currentSubscriptions;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("SseEventSink is closed");
            }
            events.add(micronautEvent);
            currentSubscriptions = List.copyOf(subscriptions);
        }
        currentSubscriptions.forEach(SinkSubscription::drain);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() throws IOException {
        List<SinkSubscription> currentSubscriptions;
        List<Consumer<SseEventSink>> listeners;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            currentSubscriptions = List.copyOf(subscriptions);
            listeners = List.copyOf(closeListeners);
        }
        currentSubscriptions.forEach(SinkSubscription::drain);
        listeners.forEach(listener -> listener.accept(this));
    }

    @Override
    public void subscribe(Subscriber<? super Event<String>> subscriber) {
        SinkSubscription subscription = new SinkSubscription(subscriber);
        synchronized (this) {
            subscriptions.add(subscription);
        }
        subscriber.onSubscribe(subscription);
        subscription.drain();
    }

    void onClose(Consumer<SseEventSink> listener) {
        synchronized (this) {
            closeListeners.add(listener);
        }
    }

    private Event<String> toMicronautEvent(OutboundSseEvent event) {
        Event<String> micronautEvent = Event.of(data(event));
        if (event.getId() != null) {
            micronautEvent.id(event.getId());
        }
        if (event.getName() != null) {
            micronautEvent.name(event.getName());
        }
        if (event.getComment() != null) {
            micronautEvent.comment(event.getComment());
        }
        if (event.isReconnectDelaySet()) {
            micronautEvent.retry(java.time.Duration.ofMillis(event.getReconnectDelay()));
        }
        return micronautEvent;
    }

    private String data(OutboundSseEvent event) {
        Object data = event.getData();
        if (data instanceof CharSequence charSequence) {
            return charSequence.toString();
        }
        if (data instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        @SuppressWarnings({"rawtypes", "unchecked"})
        Argument<Object> argument = Argument.of((Class) event.getType());
        MediaType mediaType = mediaType(event);
        MessageBodyWriter<Object> writer = writer(argument, mediaType);
        try {
            writer.createSpecific(argument).writeTo(argument, mediaType, data, new SimpleHttpHeaders(), outputStream);
        } catch (JaxRsIOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProcessingException("Cannot write SSE event data", e);
        }
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private MessageBodyWriter<Object> writer(Argument<Object> argument, MediaType mediaType) {
        Optional<MessageBodyWriter<Object>> jaxRsWriter = jaxRsHandlerRegistry.findWriter(argument, List.of(mediaType));
        if (jaxRsWriter.isPresent()) {
            return jaxRsWriter.get();
        }
        return bodyHandlerRegistry.findWriter(argument, List.of(mediaType))
            .orElseThrow(() -> new InternalServerErrorException("Cannot find MessageBodyWriter for SSE event type " + argument));
    }

    private static MediaType mediaType(OutboundSseEvent event) {
        jakarta.ws.rs.core.MediaType eventMediaType = event.getMediaType();
        if (eventMediaType == null) {
            return MediaType.TEXT_PLAIN_TYPE;
        }
        MediaType mediaType = JaxRsUtils.convert(eventMediaType);
        if (MediaType.ALL_TYPE.equals(mediaType)) {
            return MediaType.TEXT_PLAIN_TYPE;
        }
        return mediaType;
    }

    private void remove(SinkSubscription subscription) {
        synchronized (this) {
            subscriptions.remove(subscription);
        }
    }

    private static long addDemand(long current, long requested) {
        if (current == Long.MAX_VALUE || requested == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long updated = current + requested;
        return updated < 0 ? Long.MAX_VALUE : updated;
    }

    private final class SinkSubscription implements Subscription {
        private final Subscriber<? super Event<String>> subscriber;
        private int index;
        private long requested;
        private boolean cancelled;
        private boolean completed;
        private boolean draining;
        private boolean missed;

        private SinkSubscription(Subscriber<? super Event<String>> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Non-positive subscription request"));
                cancel();
                return;
            }
            synchronized (this) {
                requested = addDemand(requested, n);
            }
            drain();
        }

        @Override
        public void cancel() {
            synchronized (this) {
                cancelled = true;
            }
            remove(this);
            try {
                JaxRsSseEventSink.this.close();
            } catch (IOException e) {
                subscriber.onError(e);
            }
        }

        private void drain() {
            synchronized (this) {
                if (draining) {
                    missed = true;
                    return;
                }
                draining = true;
            }
            while (true) {
                Delivery delivery = nextDelivery();
                if (delivery.complete()) {
                    subscriber.onComplete();
                    remove(this);
                    synchronized (this) {
                        draining = false;
                    }
                    return;
                }
                Event<String> event = delivery.event();
                if (event != null) {
                    subscriber.onNext(event);
                    continue;
                }
                synchronized (this) {
                    if (missed) {
                        missed = false;
                    } else {
                        draining = false;
                        return;
                    }
                }
            }
        }

        private Delivery nextDelivery() {
            synchronized (JaxRsSseEventSink.this) {
                synchronized (this) {
                    if (cancelled || completed || requested == 0) {
                        return Delivery.NONE;
                    }
                    if (index < events.size()) {
                        Event<String> event = events.get(index++);
                        if (requested != Long.MAX_VALUE) {
                            requested--;
                        }
                        return new Delivery(event, false);
                    }
                    if (closed) {
                        completed = true;
                        return Delivery.COMPLETED;
                    }
                    return Delivery.NONE;
                }
            }
        }
    }

    private record Delivery(@Nullable Event<String> event, boolean complete) {
        private static final Delivery NONE = new Delivery(null, false);
        private static final Delivery COMPLETED = new Delivery(null, true);
    }
}
