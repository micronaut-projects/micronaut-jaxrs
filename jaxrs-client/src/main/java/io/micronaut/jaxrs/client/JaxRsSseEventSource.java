/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.http.sse.Event;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Receives the events of a target (JAX-RS 9.4): it reconnects when the connection is lost, after
 * the reconnect delay the server or the builder gave, and when the server answers
 * {@code 503 Service Unavailable}, after its {@code Retry-After}.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsSseEventSource implements SseEventSource {

    private final JaxRsWebTarget target;
    private final List<Consumer<InboundSseEvent>> onEvent = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> onError = new CopyOnWriteArrayList<>();
    private final List<Runnable> onComplete = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jaxrs-sse-event-source");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long reconnectDelay;
    private volatile boolean open;
    private volatile @Nullable String lastEventId;
    private volatile @Nullable Subscription subscription;
    // an own client: closing it disconnects, which the server sees, where cancelling a stream drains it
    private volatile @Nullable DefaultHttpClient httpClient;

    JaxRsSseEventSource(JaxRsWebTarget target, long reconnectDelay) {
        this.target = target;
        this.reconnectDelay = reconnectDelay;
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent) {
        this.onEvent.add(onEvent);
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent, Consumer<Throwable> onError) {
        register(onEvent);
        this.onError.add(onError);
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent, Consumer<Throwable> onError, Runnable onComplete) {
        register(onEvent, onError);
        this.onComplete.add(onComplete);
    }

    @Override
    public synchronized void open() {
        if (open) {
            throw new IllegalStateException("The event source is open");
        }
        open = true;
        connect();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean close(long timeout, TimeUnit unit) {
        open = false;
        Subscription current = subscription;
        if (current != null) {
            current.cancel();
        }
        DefaultHttpClient client = httpClient;
        if (client != null) {
            client.close();
        }
        scheduler.shutdownNow();
        try {
            return scheduler.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void connect() {
        if (!open) {
            return;
        }
        MutableHttpRequest<Object> request = HttpRequest.GET(target.getUri().toString()).accept(MediaType.TEXT_EVENT_STREAM_TYPE);
        String id = lastEventId;
        if (id != null) {
            request.header("Last-Event-ID", id);
        }
        DefaultHttpClient client = httpClient;
        if (client == null) {
            client = new DefaultHttpClient((URI) null, new DefaultHttpClientConfiguration());
            httpClient = client;
        }
        client.eventStream(request, Argument.STRING).subscribe(new Subscriber<Event<String>>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Event<String> event) {
                received(event);
            }

            @Override
            public void onError(Throwable throwable) {
                failed(throwable);
            }

            @Override
            public void onComplete() {
                // the connection is lost: reconnect
                reconnect(reconnectDelay);
            }
        });
    }

    private void received(Event<String> event) {
        long delay = InboundSseEvent.RECONNECT_NOT_SET;
        Duration retry = event.getRetry();
        if (retry != null) {
            delay = retry.toMillis();
            reconnectDelay = delay;
        }
        if (event.getId() != null) {
            lastEventId = event.getId();
        }
        JaxRsInboundSseEvent inbound = new JaxRsInboundSseEvent(event.getName(), event.getId(), event.getComment(), delay,
            event.getData(), target.getConfiguration());
        for (Consumer<InboundSseEvent> consumer : onEvent) {
            consumer.accept(inbound);
        }
    }

    private void failed(Throwable throwable) {
        if (throwable instanceof HttpClientResponseException responseException
            && responseException.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
            // the server is not available yet: retry after the time it asked for, or the reconnect delay
            long delay = responseException.getResponse().getHeaders().get("Retry-After", Long.class)
                .map(TimeUnit.SECONDS::toMillis)
                .orElse(reconnectDelay);
            reconnect(delay);
            return;
        }
        open = false;
        for (Consumer<Throwable> consumer : onError) {
            consumer.accept(throwable);
        }
    }

    private void reconnect(long delay) {
        if (!open) {
            for (Runnable callback : onComplete) {
                callback.run();
            }
            return;
        }
        try {
            scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // closed meanwhile
            open = false;
        }
    }

    /**
     * The builder of an event source, found with the {@link java.util.ServiceLoader}.
     */
    public static final class Builder extends SseEventSource.Builder {
        private static final long DEFAULT_RECONNECT_DELAY = 500;

        private @Nullable JaxRsWebTarget target;
        private long reconnectDelay = DEFAULT_RECONNECT_DELAY;

        @Override
        protected Builder target(jakarta.ws.rs.client.WebTarget endpoint) {
            if (!(endpoint instanceof JaxRsWebTarget webTarget)) {
                throw new IllegalArgumentException("Not a target of the Micronaut client: " + endpoint);
            }
            this.target = webTarget;
            return this;
        }

        @Override
        public Builder reconnectingEvery(long delay, TimeUnit unit) {
            this.reconnectDelay = unit.toMillis(delay);
            return this;
        }

        @Override
        public SseEventSource build() {
            if (target == null) {
                throw new IllegalStateException("No target");
            }
            return new JaxRsSseEventSource(target, reconnectDelay);
        }
    }
}
