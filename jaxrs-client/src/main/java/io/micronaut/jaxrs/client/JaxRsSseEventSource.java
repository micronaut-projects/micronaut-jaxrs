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
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.body.BodyElements;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.http.client.sse.EventStreamDecoder;
import io.micronaut.http.client.sse.EventStreamState;
import io.micronaut.http.sse.Event;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ResponseProcessingException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Receives the events of a target (JAX-RS 9.4): it reconnects when the connection is lost, after
 * the reconnect delay the server or the builder gave, and when the server answers
 * {@code 503 Service Unavailable} with a valid {@code Retry-After}, after it. The reconnects are
 * scheduled on the scheduled executor service of the client builder, else on a shared one. The
 * events, the errors and the completion are processed in order on the executor service of the
 * client, not on the event loop of the HTTP client.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsSseEventSource implements SseEventSource {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsSseEventSource.class);

    /**
     * Releases the connections of the closed event sources, which may block: its threads do not
     * keep the JVM alive.
     */
    private static final Executor CLOSER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jaxrs-sse-event-source-close");
        thread.setDaemon(true);
        return thread;
    });

    private final JaxRsWebTarget target;
    private final ScheduledExecutorService scheduler;
    // the consumers, and the execution of the callbacks, which outlives the connection
    private final JaxRsSseCallbacks callbacks;
    // whether a connection received an event stream: then a broken connection is reconnected
    private volatile boolean connected;
    // the reconnect delay of the builder, unless the stream gave a reconnection time
    private final long configuredReconnectDelay;
    // the state, changed under this lock: connect and close are atomic to each other
    private volatile boolean open;
    // whether the source was opened: it is not opened again (JAX-RS, SseEventSource#open)
    private volatile boolean opened;
    private volatile boolean closed;
    // the last event id and reconnection time of the stream, which the client follows also for a
    // block without data: one per source, kept across connections
    private final EventStreamState streamState = new EventStreamState();
    private volatile @Nullable BodyElements<ByteBuffer<?>> events;
    // the entity stream the events are read from: the bytes of the connection, or the stream a filter set
    private volatile @Nullable InputStream replacedStream;
    private volatile @Nullable Future<?> pendingReconnect;
    // an own client: closing it disconnects, which the server sees, where cancelling a stream drains it
    private volatile @Nullable DefaultHttpClient httpClient;

    JaxRsSseEventSource(JaxRsWebTarget target, long reconnectDelay) {
        this.target = target;
        this.configuredReconnectDelay = reconnectDelay;
        ScheduledExecutorService clientScheduler = target.client().getScheduledExecutorService();
        this.scheduler = clientScheduler != null ? clientScheduler : DefaultScheduler.INSTANCE;
        this.callbacks = new JaxRsSseCallbacks(target.client().getExecutorService());
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent) {
        callbacks.onEvent(requireNonNull("onEvent", onEvent));
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent, Consumer<Throwable> onError) {
        requireNonNull("onEvent", onEvent);
        requireNonNull("onError", onError);
        register(onEvent);
        callbacks.onError(onError);
    }

    @Override
    public void register(Consumer<InboundSseEvent> onEvent, Consumer<Throwable> onError, Runnable onComplete) {
        requireNonNull("onEvent", onEvent);
        requireNonNull("onError", onError);
        requireNonNull("onComplete", onComplete);
        register(onEvent, onError);
        callbacks.onComplete(onComplete);
    }

    private static <T> T requireNonNull(String name, @Nullable T value) {
        if (value == null) {
            // JAX-RS, SseEventSource#register
            throw new IllegalArgumentException(name + " cannot be null");
        }
        return value;
    }

    @Override
    public void open() {
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("The event source is closed");
            }
            if (opened) {
                throw new IllegalStateException("The event source was opened");
            }
            opened = true;
            open = true;
        }
        target.client().opened(this);
        // without the lock: the request filters may block, and a close does not wait for them
        connect();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean close(long timeout, TimeUnit unit) {
        Future<?> reconnect;
        BodyElements<ByteBuffer<?>> current;
        InputStream replaced;
        DefaultHttpClient client;
        boolean closedAgain;
        synchronized (this) {
            closedAgain = closed;
            reconnect = pendingReconnect;
            current = events;
            replaced = replacedStream;
            client = httpClient;
            closed = true;
            open = false;
            httpClient = null;
        }
        if (closedAgain) {
            // closed again: true only once the processing completed (JAX-RS, SseEventSource#close),
            // waited for without the lock, which a callback closing the source would wait for
            return callbacks.awaitProcessed(timeout, unit);
        }
        target.client().closed(this);
        if (reconnect != null) {
            reconnect.cancel(false);
        }
        if (current != null) {
            current.closeAsync();
        }
        // one deadline for the release of the connection and the processing
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        CompletableFuture<Void> released = CompletableFuture.completedFuture(null);
        if (replaced != null || client != null) {
            // closing the stream a filter set unblocks the read of the events, and closing the
            // client disconnects: both may block, waited for until the deadline only
            Runnable release = () -> {
                if (replaced != null) {
                    closeQuietly(replaced);
                }
                if (client != null) {
                    client.close();
                }
            };
            try {
                released = CompletableFuture.runAsync(release, CLOSER);
            } catch (RejectedExecutionException e) {
                release.run();
            }
        }
        // the processing ends, e.g. before a pending reconnect (JAX-RS, SseEventSource#register)
        complete();
        try {
            released.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            LOG.debug("Releasing the connection failed: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return callbacks.awaitProcessed(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    /**
     * @return The HTTP client of the connection, {@code null} before connecting and after closing
     */
    @Nullable DefaultHttpClient httpClient() {
        return httpClient;
    }

    private void connect() {
        if (!open) {
            return;
        }
        MutableHttpRequest<Object> request = HttpRequest.GET(target.getUri().toString()).accept(MediaType.TEXT_EVENT_STREAM_TYPE);
        String id = streamState.getLastEventId();
        if (id != null && !id.isEmpty()) {
            // an empty id resets the last one: no header (HTML 9.2.6)
            request.header("Last-Event-ID", id);
        }
        request.setAttribute(EventStreamState.ATTRIBUTE, streamState);
        JaxRsClientRequestContext requestContext = filtered(request);
        if (requestContext == null) {
            return;
        }
        // the method and the entity a filter set, as in an ordinary exchange
        MutableHttpRequest<Object> sent;
        try {
            sent = JaxRsClientExchange.changed(target.getConfiguration(), request, requestContext, null);
        } catch (IOException | RuntimeException e) {
            error(e instanceof ProcessingException processing ? processing : new ProcessingException(e));
            return;
        }
        DefaultHttpClient client;
        synchronized (this) {
            // a close before the lock wins: no client is created after it
            if (!open) {
                return;
            }
            client = httpClient;
            if (client == null) {
                // configured like the HTTP client of the JAX-RS client: its timeouts and SSL
                client = target.client().newHttpClient();
                httpClient = client;
            }
        }
        // the response filters may block: they run on the executor of the client, not on the
        // event loop of the HTTP client (JAX-RS, ClientBuilder#executorService)
        client.toAsyncStreaming().exchangeStream(sent).whenComplete((response, error) -> callbacks.dispatch(() -> {
            if (error != null) {
                failed(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error, requestContext);
            } else {
                receive(response, requestContext);
            }
        }));
    }

    /**
     * Receive the events of a response: a {@code 204 No Content} has none and ends the
     * processing, and a response that is not an event stream is an error (JAX-RS 9.4).
     */
    private void receive(HttpResponse<BodyElements<ByteBuffer<?>>> response, JaxRsClientRequestContext requestContext) {
        BodyElements<ByteBuffer<?>> body = response.body();
        if (!open) {
            if (body != null) {
                body.closeAsync();
            }
            return;
        }
        // the response filters see the status and the headers of each connection, before its
        // events (JAX-RS 6.1), and may change them
        MutableHttpResponse<?> head = HttpResponse.status(response.status(), response.reason());
        response.getHeaders().forEachValue(head::header);
        JaxRsMutableResponse filtered = new JaxRsMutableResponse(head);
        // the entity stream a filter sees is the bytes of the connection as they are received; it
        // may replace it, e.g. to decode or to wrap it: the events are decoded from the result
        InputStream unchanged = body == null ? InputStream.nullInputStream() : new JaxRsBodyInputStream(body);
        filtered.setEntityStream(unchanged);
        try {
            filterResponse(filtered, requestContext);
        } catch (IOException | RuntimeException e) {
            if (body != null) {
                body.closeAsync();
            }
            closeReplaced(filtered, unchanged);
            error(new ResponseProcessingException(filtered, e));
            return;
        }
        process(filtered, unchanged, body);
    }

    /**
     * Process a filtered response, also an error response a filter repaired: a status other than
     * {@code 200 OK} is an error, else its events are received from its entity stream, the one a
     * filter set or the events of the connection.
     *
     * @param filtered  The filtered response
     * @param unchanged The entity stream before the filters
     * @param body      The events of the connection, {@code null} for an error response
     */
    private void process(JaxRsMutableResponse filtered, InputStream unchanged, @Nullable BodyElements<ByteBuffer<?>> body) {
        InputStream replacement = filtered.getEntityStream() == unchanged ? null : filtered.getEntityStream();
        int status = filtered.getStatus();
        if (status != HttpStatus.OK.getCode() && status != HttpStatus.NO_CONTENT.getCode()) {
            if (body != null) {
                // the events are not an entity of the error
                body.closeAsync();
                closeReplaced(filtered, unchanged);
                filtered.setEntityStream(InputStream.nullInputStream());
            }
            // a 503 a filter set is retried like one of the server: the response is discarded
            if (retried(filtered)) {
                closeReplaced(filtered, unchanged);
            } else {
                // another status is an error, e.g. a 201, with the filtered response (JAX-RS, SseEventSource)
                error(JaxRsClientExchange.exception(filtered));
            }
            return;
        }
        if (status == HttpStatus.NO_CONTENT.getCode() || body == null && replacement == null) {
            if (body != null) {
                body.closeAsync();
            }
            closeReplaced(filtered, unchanged);
            open = false;
            complete();
            return;
        }
        MediaType contentType = contentType(filtered);
        if (contentType == null || !MediaType.TEXT_EVENT_STREAM_TYPE.matches(contentType)) {
            if (body != null) {
                body.closeAsync();
            }
            closeReplaced(filtered, unchanged);
            filtered.setEntityStream(InputStream.nullInputStream());
            // an error with the filtered response (JAX-RS, SseEventSource)
            error(new WebApplicationException("The response is not an event stream: " + contentType, filtered));
            return;
        }
        // the events are decoded from the entity stream, the one a filter set, which may read the
        // bytes of the connection, else these bytes: they are closed with the source, or once the
        // stream is read
        InputStream stream = replacement != null ? replacement : unchanged;
        if (body != null) {
            synchronized (this) {
                if (!open) {
                    body.closeAsync();
                    closeQuietly(stream);
                    return;
                }
                events = body;
            }
        }
        receive(stream);
    }

    /**
     * Receive the events of the entity stream, the bytes of the connection or the stream a response
     * filter set: they are read and processed
     * on the executor, in order, until the stream ends or the source is closed, which closes it.
     */
    private void receive(InputStream stream) {
        synchronized (this) {
            if (!open) {
                closeQuietly(stream);
                return;
            }
            replacedStream = stream;
            connected = true;
        }
        DefaultHttpClient client = httpClient;
        long maxLength = client == null ? Integer.MAX_VALUE : client.getConfiguration().getMaxContentLength();
        var decoder = new EventStreamDecoder(maxLength, streamState);
        byte[] buffer = new byte[8192];
        Throwable failure = null;
        try {
            int read;
            while (open && (read = stream.read(buffer)) != -1) {
                // the consumer overload leaves the ids to deliver(), so they are taken once, in order
                decoder.decode(buffer, 0, read, event -> {
                    if (EventStreamDecoder.deliver(event, streamState)) {
                        // already on the executor: the consumers process the event before the next one is read
                        callbacks.deliver(inbound(Event.of(event, new String(event.getData(), StandardCharsets.UTF_8))));
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            failure = e;
        } finally {
            replacedStream = null;
            closeQuietly(stream);
            BodyElements<ByteBuffer<?>> read = events;
            events = null;
            if (read != null) {
                read.closeAsync();
            }
        }
        if (!open) {
            return;
        }
        if (failure != null) {
            LOG.debug("The event stream failed, reconnecting: {}", failure.getMessage(), failure);
        }
        reconnect(reconnectDelay());
    }

    private static @Nullable MediaType contentType(Response response) {
        String value = response.getHeaderString(HttpHeaders.CONTENT_TYPE);
        if (value == null) {
            return null;
        }
        try {
            return MediaType.of(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void closeReplaced(JaxRsMutableResponse response, InputStream unchanged) {
        InputStream stream = response.getEntityStream();
        if (stream != unchanged) {
            closeQuietly(stream);
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            LOG.debug("Closing an entity stream failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Run the request filters of the target, e.g. one adding credentials.
     *
     * @return The context of the request to send, {@code null} if a filter aborted it
     */
    private @Nullable JaxRsClientRequestContext filtered(MutableHttpRequest<Object> request) {
        JaxRsConfiguration configuration = target.getConfiguration();
        var requestContext = new JaxRsClientRequestContext(target.client(), configuration, request, null, Argument.OBJECT_ARGUMENT, Map.of());
        // the injected configuration of a shared filter is the one of this target (JAX-RS 10.2.8)
        try {
            Response response = JaxRsClientFilters.filterRequest(configuration, requestContext);
            if (response != null) {
                // the entity stream a filter replaced writes nothing
                requestContext.closeReplacedEntityStream(null);
                aborted(response, requestContext);
                return null;
            }
        } catch (IOException | RuntimeException e) {
            requestContext.closeReplacedEntityStream(e);
            error(new ProcessingException(e));
            return null;
        }
        return requestContext;
    }

    /**
     * Process the response a request filter aborted with: the response filters see it like a
     * response of the server (JAX-RS 6.2), then it ends the processing, without events.
     */
    private void aborted(Response response, JaxRsClientRequestContext requestContext) {
        JaxRsMutableResponse filtered = JaxRsClientExchange.toMutableResponse(response);
        try {
            filterResponse(filtered, requestContext);
        } catch (IOException | RuntimeException e) {
            error(new ResponseProcessingException(filtered, e));
            return;
        }
        if (filtered.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            // nothing to receive: its entity, e.g. a stream, is not read
            try {
                filtered.close();
            } catch (ProcessingException e) {
                LOG.debug("Closing an aborted response failed: {}", e.getMessage(), e);
            }
            open = false;
            complete();
        } else {
            error(JaxRsClientExchange.exception(filtered));
        }
    }

    /**
     * Run the response filters of the target on the response of a connection.
     */
    private void filterResponse(JaxRsMutableResponse response, JaxRsClientRequestContext requestContext) throws IOException {
        JaxRsClientFilters.filterResponse(target.getConfiguration(), requestContext, response);
    }

    /**
     * @return The event the consumers receive: the state of the stream follows its id and retry for a reconnect
     */
    private InboundSseEvent inbound(Event<String> event) {
        long delay = InboundSseEvent.RECONNECT_NOT_SET;
        Duration retry = event.getRetry();
        if (retry != null) {
            delay = retry.toMillis();
        }
        return new JaxRsInboundSseEvent(event.getName(), event.getId(), event.getComment(), delay,
            event.getData(), target.getConfiguration());
    }

    private void failed(Throwable throwable, JaxRsClientRequestContext requestContext) {
        if (throwable instanceof HttpClientResponseException responseException) {
            // the response filters see an error response like any other (JAX-RS 6.1)
            JaxRsMutableResponse response = new JaxRsMutableResponse(responseException.getResponse().toMutableResponse());
            InputStream unchanged = response.getEntityStream();
            try {
                filterResponse(response, requestContext);
            } catch (IOException | RuntimeException e) {
                closeReplaced(response, unchanged);
                error(new ResponseProcessingException(response, e));
                return;
            }
            // processed like any other response: a filter may turn the error into an event stream
            process(response, unchanged, null);
            return;
        }
        if (connected) {
            // a transport error while reconnecting, e.g. the server is restarting: retry later
            LOG.debug("The reconnection failed, retrying: {}", throwable.getMessage(), throwable);
            reconnect(reconnectDelay());
            return;
        }
        error(throwable);
    }

    /**
     * Reconnect after a filtered {@code 503 Service Unavailable} response with a valid
     * {@code Retry-After} header: the server is not available yet (JAX-RS, SseEventSource).
     *
     * @return Whether the source reconnects
     */
    private boolean retried(Response response) {
        if (response.getStatus() != HttpStatus.SERVICE_UNAVAILABLE.getCode()) {
            return false;
        }
        Long delay = retryAfter(response.getHeaderString(HttpHeaders.RETRY_AFTER));
        if (delay == null) {
            return false;
        }
        reconnect(delay);
        return true;
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
        if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            // delay-seconds: digits only, no sign (RFC 9110, 10.2.3)
            try {
                return TimeUnit.SECONDS.toMillis(Long.parseLong(value));
            } catch (NumberFormatException e) {
                // too large: no reconnect in time
                return null;
            }
        }
        try {
            // any of the formats of RFC 9110, 5.6.7
            Date date = RuntimeDelegate.getInstance().createHeaderDelegate(Date.class).fromString(value);
            return Math.max(0, date.getTime() - System.currentTimeMillis());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void error(Throwable throwable) {
        open = false;
        callbacks.error(throwable);
    }

    private void complete() {
        callbacks.complete();
    }

    /**
     * @return The delay of a reconnection: the reconnection time of the stream, also of a block
     * without data, otherwise the configured one
     */
    private long reconnectDelay() {
        Duration time = streamState.getReconnectionTime();
        return time == null ? configuredReconnectDelay : time.toMillis();
    }

    private void reconnect(long delay) {
        synchronized (this) {
            if (open) {
                try {
                    // the timer only dispatches the connection: its request filters may block, and
                    // the scheduler may be shared by other sources
                    pendingReconnect = scheduler.schedule(this::dispatchConnect, delay, TimeUnit.MILLISECONDS);
                    return;
                } catch (RuntimeException e) {
                    // the scheduler is shut down
                    error(new ProcessingException(e));
                    return;
                }
            }
        }
        complete();
    }

    /**
     * Connect on the executor of the client, e.g. for a scheduled reconnection.
     */
    private void dispatchConnect() {
        try {
            target.client().getExecutorService().execute(this::connect);
        } catch (RejectedExecutionException e) {
            error(new ProcessingException(e));
        }
    }

    /**
     * @return The number of callbacks dispatched and not yet run
     */
    int pendingCallbacks() {
        return callbacks.pendingCallbacks();
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
