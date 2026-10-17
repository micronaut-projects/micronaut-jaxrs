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
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.body.BodyElements;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.http.sse.Event;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Receives the events of a target (JAX-RS 9.4): it reconnects when the connection is lost, after
 * the reconnect delay the server or the builder gave, and when the server answers
 * {@code 503 Service Unavailable} with a valid {@code Retry-After}, after it. The reconnects are
 * scheduled on the scheduled executor service of the client builder, else on a shared one.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsSseEventSource implements SseEventSource {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsSseEventSource.class);

    private final JaxRsWebTarget target;
    private final List<Consumer<InboundSseEvent>> onEvent = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> onError = new CopyOnWriteArrayList<>();
    private final List<Runnable> onComplete = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    // whether onComplete or onError was invoked: the processing ended
    private final AtomicBoolean ended = new AtomicBoolean();
    private volatile long reconnectDelay;
    private volatile boolean open;
    private volatile boolean closed;
    private volatile @Nullable String lastEventId;
    private volatile @Nullable BodyElements<Event<String>> events;
    private volatile @Nullable Future<?> pendingReconnect;
    // an own client: closing it disconnects, which the server sees, where cancelling a stream drains it
    private volatile @Nullable DefaultHttpClient httpClient;

    JaxRsSseEventSource(JaxRsWebTarget target, long reconnectDelay) {
        this.target = target;
        this.reconnectDelay = reconnectDelay;
        ScheduledExecutorService clientScheduler = target.client().getScheduledExecutorService();
        this.scheduler = clientScheduler != null ? clientScheduler : DefaultScheduler.INSTANCE;
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent) {
        this.onEvent.add(requireNonNull("onEvent", onEvent));
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent, Consumer<Throwable> onError) {
        requireNonNull("onEvent", onEvent);
        requireNonNull("onError", onError);
        register(onEvent);
        this.onError.add(onError);
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent, Consumer<Throwable> onError, Runnable onComplete) {
        requireNonNull("onEvent", onEvent);
        requireNonNull("onError", onError);
        requireNonNull("onComplete", onComplete);
        register(onEvent, onError);
        this.onComplete.add(onComplete);
    }

    private static <T> T requireNonNull(String name, @Nullable T value) {
        if (value == null) {
            // JAX-RS, SseEventSource#register
            throw new IllegalArgumentException(name + " cannot be null");
        }
        return value;
    }

    @Override
    public synchronized void open() {
        if (closed) {
            throw new IllegalStateException("The event source is closed");
        }
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
        if (closed) {
            return true;
        }
        closed = true;
        open = false;
        Future<?> reconnect = pendingReconnect;
        if (reconnect != null) {
            reconnect.cancel(false);
        }
        BodyElements<Event<String>> current = events;
        if (current != null) {
            current.closeAsync();
        }
        DefaultHttpClient client = httpClient;
        if (client != null) {
            client.close();
        }
        // the processing ends, e.g. before a pending reconnect (JAX-RS, SseEventSource#register)
        complete();
        return true;
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
        if (!filtered(request)) {
            return;
        }
        DefaultHttpClient client = httpClient;
        if (client == null) {
            // configured like the HTTP client of the JAX-RS client: its timeouts and SSL
            client = target.client().newHttpClient();
            httpClient = client;
        }
        client.toAsyncStreaming().exchangeEventStream(request, Argument.STRING).whenComplete((response, error) -> {
            if (error != null) {
                failed(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
            } else {
                receive(response);
            }
        });
    }

    /**
     * Receive the events of a response: a {@code 204 No Content} has none and ends the
     * processing, and a response that is not an event stream is an error (JAX-RS 9.4).
     */
    private void receive(HttpResponse<BodyElements<Event<String>>> response) {
        BodyElements<Event<String>> body = response.body();
        if (!open) {
            if (body != null) {
                body.closeAsync();
            }
            return;
        }
        if (response.code() == HttpStatus.NO_CONTENT.getCode() || body == null) {
            if (body != null) {
                body.closeAsync();
            }
            open = false;
            complete();
            return;
        }
        MediaType contentType = response.getContentType().orElse(null);
        if (contentType == null || !MediaType.TEXT_EVENT_STREAM_TYPE.matches(contentType)) {
            body.closeAsync();
            error(new ProcessingException("The response is not an event stream: " + contentType));
            return;
        }
        events = body;
        body.forEach(event -> {
            received(event);
            return CompletableFuture.completedFuture(null);
        }).whenComplete((done, error) -> {
            events = null;
            if (!open) {
                return;
            }
            if (error != null) {
                failed(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
            } else {
                // the connection is lost: reconnect
                reconnect(reconnectDelay);
            }
        });
    }

    /**
     * Run the request filters of the target, e.g. one adding credentials.
     *
     * @return Whether the request is sent, else a filter aborted it
     */
    private boolean filtered(MutableHttpRequest<Object> request) {
        JaxRsConfiguration configuration = target.getConfiguration();
        var requestContext = new JaxRsClientRequestContext(target.client(), configuration, request, null, Argument.OBJECT_ARGUMENT, Map.of());
        try {
            for (ClientRequestFilter filter : configuration.getRequestFilters()) {
                filter.filter(requestContext);
                Response response = requestContext.getResponse();
                if (response != null) {
                    if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                        // nothing to receive
                        open = false;
                        complete();
                    } else {
                        error(JaxRsInvocation.exception(response));
                    }
                    return false;
                }
            }
        } catch (IOException | RuntimeException e) {
            error(new ProcessingException(e));
            return false;
        }
        return true;
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
            try {
                consumer.accept(inbound);
            } catch (RuntimeException e) {
                // a failing consumer does not stop the processing of the events
                LOG.warn("An event consumer failed: {}", e.getMessage(), e);
            }
        }
    }

    private void failed(Throwable throwable) {
        if (throwable instanceof HttpClientResponseException responseException) {
            if (responseException.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                // the server is not available yet: retry after the time it asked for
                Long delay = retryAfter(responseException.getResponse().getHeaders().get(HttpHeaders.RETRY_AFTER));
                if (delay != null) {
                    reconnect(delay);
                    return;
                }
            }
            error(JaxRsInvocation.exception(new JaxRsMutableResponse(responseException.getResponse().toMutableResponse())));
            return;
        }
        error(throwable);
    }

    /**
     * The delay of a {@code Retry-After} header, in milliseconds: its seconds or its date.
     *
     * @return The delay, {@code null} if the header is missing or not valid
     */
    static @Nullable Long retryAfter(@Nullable String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }
        String value = retryAfter.strip();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : TimeUnit.SECONDS.toMillis(seconds);
        } catch (NumberFormatException e) {
            // an HTTP date
        }
        try {
            ZonedDateTime date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, Duration.between(ZonedDateTime.now(date.getZone()), date).toMillis());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void error(Throwable throwable) {
        open = false;
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        if (onError.isEmpty()) {
            LOG.warn("The event source failed: {}", throwable.getMessage(), throwable);
        }
        for (Consumer<Throwable> consumer : onError) {
            try {
                consumer.accept(throwable);
            } catch (RuntimeException e) {
                LOG.warn("An error consumer failed: {}", e.getMessage(), e);
            }
        }
    }

    private void complete() {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        for (Runnable callback : onComplete) {
            try {
                callback.run();
            } catch (RuntimeException e) {
                LOG.warn("A completion callback failed: {}", e.getMessage(), e);
            }
        }
    }

    private void reconnect(long delay) {
        if (!open) {
            complete();
            return;
        }
        try {
            pendingReconnect = scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // the scheduler is shut down
            error(new ProcessingException(e));
        }
    }

    /**
     * The scheduler of the event sources of the clients built without a scheduled executor
     * service: its thread does not keep the JVM alive.
     */
    private static final class DefaultScheduler {
        static final ScheduledExecutorService INSTANCE = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jaxrs-sse-event-source");
            thread.setDaemon(true);
            return thread;
        });
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
