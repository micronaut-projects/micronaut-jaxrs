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
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.body.BodyElements;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.http.client.sse.EventStreamDecoder;
import io.micronaut.http.client.sse.EventStreamState;
import io.micronaut.http.sse.Event;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.ws.rs.ProcessingException;
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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final JaxRsWebTarget target;
    private final List<Consumer<InboundSseEvent>> onEvent = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> onError = new CopyOnWriteArrayList<>();
    private final List<Runnable> onComplete = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final Executor executor;
    // the callbacks to run on the executor, one at a time and in order
    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCallbacks = new AtomicInteger();
    // completed once the callbacks dispatched before the end of the processing ran
    private final CompletableFuture<@Nullable Void> processed = new CompletableFuture<>();
    // the thread running the callbacks, which close cannot wait for
    private volatile @Nullable Thread callbackThread;
    // whether onComplete or onError was invoked: the processing ended
    private final AtomicBoolean ended = new AtomicBoolean();
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
    private volatile @Nullable BodyElements<Event<String>> events;
    // the entity stream a response filter set, which the events are read from
    private volatile @Nullable InputStream replacedStream;
    private volatile @Nullable Future<?> pendingReconnect;
    // an own client: closing it disconnects, which the server sees, where cancelling a stream drains it
    private volatile @Nullable DefaultHttpClient httpClient;

    JaxRsSseEventSource(JaxRsWebTarget target, long reconnectDelay) {
        this.target = target;
        this.configuredReconnectDelay = reconnectDelay;
        ScheduledExecutorService clientScheduler = target.client().getScheduledExecutorService();
        this.scheduler = clientScheduler != null ? clientScheduler : DefaultScheduler.INSTANCE;
        this.executor = target.client().getExecutorService();
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
        if (opened) {
            throw new IllegalStateException("The event source was opened");
        }
        opened = true;
        open = true;
        target.client().opened(this);
        connect();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean close(long timeout, TimeUnit unit) {
        Future<?> reconnect;
        BodyElements<Event<String>> current;
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
            return awaitProcessed(timeout, unit);
        }
        target.client().closed(this);
        if (reconnect != null) {
            reconnect.cancel(false);
        }
        if (current != null) {
            current.closeAsync();
        }
        if (replaced != null) {
            // unblocks the read of the events
            closeQuietly(replaced);
        }
        if (client != null) {
            client.close();
        }
        // the processing ends, e.g. before a pending reconnect (JAX-RS, SseEventSource#register)
        complete();
        return awaitProcessed(timeout, unit);
    }

    /**
     * Wait for the callbacks to run, up to the timeout (JAX-RS, SseEventSource#close).
     */
    private boolean awaitProcessed(long timeout, TimeUnit unit) {
        if (callbackThread == Thread.currentThread()) {
            // closed by a consumer: its callbacks run after it returns
            return processed.isDone();
        }
        try {
            processed.get(timeout, unit);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
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
        if (id != null) {
            request.header("Last-Event-ID", id);
        }
        request.setAttribute(EventStreamState.ATTRIBUTE, streamState);
        JaxRsClientRequestContext requestContext = filtered(request);
        if (requestContext == null) {
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
        client.toAsyncStreaming().exchangeEventStream(request, Argument.STRING).whenComplete((response, error) -> dispatch(() -> {
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
    private void receive(HttpResponse<BodyElements<Event<String>>> response, JaxRsClientRequestContext requestContext) {
        BodyElements<Event<String>> body = response.body();
        if (!open) {
            if (body != null) {
                body.closeAsync();
            }
            return;
        }
        // the response filters see the status and the headers of each connection, before its
        // events (JAX-RS 6.1), and may change them: the events are not an entity they can read
        MutableHttpResponse<?> head = HttpResponse.status(response.status(), response.reason());
        response.getHeaders().forEachValue(head::header);
        JaxRsMutableResponse filtered = new JaxRsMutableResponse(head);
        // a filter may replace the entity stream, e.g. to decode it: the events are then read from it
        InputStream unchanged = InputStream.nullInputStream();
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
        InputStream replacement = filtered.getEntityStream() == unchanged ? null : filtered.getEntityStream();
        if (replacement != null && body != null) {
            // the events of the connection are the ones of the stream the filter set
            body.closeAsync();
            body = null;
        }
        int status = filtered.getStatus();
        if (status != HttpStatus.OK.getCode() && status != HttpStatus.NO_CONTENT.getCode()) {
            // another status is an error, e.g. a 201 (JAX-RS, SseEventSource)
            if (body != null) {
                body.closeAsync();
            }
            closeReplaced(filtered, unchanged);
            error(JaxRsClientExchange.exception(Response.status(status).build()));
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
        MediaType contentType = head.getContentType().orElse(null);
        if (contentType == null || !MediaType.TEXT_EVENT_STREAM_TYPE.matches(contentType)) {
            if (body != null) {
                body.closeAsync();
            }
            closeReplaced(filtered, unchanged);
            error(new ProcessingException("The response is not an event stream: " + contentType));
            return;
        }
        if (body == null) {
            // the events are the ones of the stream a filter set
            if (replacement != null) {
                receive(replacement);
            }
            return;
        }
        synchronized (this) {
            if (!open) {
                body.closeAsync();
                return;
            }
            events = body;
            connected = true;
        }
        // the next event is read once the consumers processed this one: a slow consumer slows the stream
        body.forEach(this::received).whenComplete((done, error) -> {
            events = null;
            if (!open) {
                return;
            }
            if (error != null) {
                LOG.debug("The event stream failed, reconnecting: {}", error.getMessage(), error);
            }
            // the connection is lost, closed by the server or broken: reconnect
            reconnect(reconnectDelay());
        });
    }

    /**
     * Receive the events of the entity stream a response filter set: they are read and processed
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
                        deliver(inbound(Event.of(event, new String(event.getData(), StandardCharsets.UTF_8))));
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            failure = e;
        } finally {
            replacedStream = null;
            closeQuietly(stream);
        }
        if (!open) {
            return;
        }
        if (failure != null) {
            LOG.debug("The event stream failed, reconnecting: {}", failure.getMessage(), failure);
        }
        reconnect(reconnectDelay());
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
                aborted(response, requestContext);
                return null;
            }
        } catch (IOException | RuntimeException e) {
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
            // nothing to receive
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
     * Dispatch an event to the consumers.
     *
     * @return The stage completed once the consumers processed it
     */
    private CompletableFuture<@Nullable Void> received(Event<String> event) {
        InboundSseEvent inbound = inbound(event);
        CompletableFuture<@Nullable Void> delivered = new CompletableFuture<>();
        dispatch(() -> {
            try {
                deliver(inbound);
            } finally {
                delivered.complete(null);
            }
        });
        return delivered;
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

    private void deliver(InboundSseEvent inbound) {
        if (ended.get()) {
            return;
        }
        for (Consumer<InboundSseEvent> consumer : onEvent) {
            try {
                consumer.accept(inbound);
            } catch (RuntimeException e) {
                // a failing consumer does not stop the processing of the events
                LOG.warn("An event consumer failed: {}", e.getMessage(), e);
            }
        }
    }

    private void failed(Throwable throwable, JaxRsClientRequestContext requestContext) {
        if (throwable instanceof HttpClientResponseException responseException) {
            // the response filters see an error response like any other (JAX-RS 6.1)
            JaxRsMutableResponse response = new JaxRsMutableResponse(responseException.getResponse().toMutableResponse());
            try {
                filterResponse(response, requestContext);
            } catch (IOException | RuntimeException e) {
                error(new ResponseProcessingException(response, e));
                return;
            }
            if (response.getStatus() == HttpStatus.SERVICE_UNAVAILABLE.getCode()) {
                // the server is not available yet: retry after the time it asked for
                Long delay = retryAfter(response.getHeaderString(HttpHeaders.RETRY_AFTER));
                if (delay != null) {
                    reconnect(delay);
                    return;
                }
            }
            if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                // a filter turned the error into a success, without events
                open = false;
                complete();
                return;
            }
            error(JaxRsClientExchange.exception(response));
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
            // any of the formats of RFC 9110, 5.6.7
            Date date = RuntimeDelegate.getInstance().createHeaderDelegate(Date.class).fromString(value);
            return Math.max(0, date.getTime() - System.currentTimeMillis());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void error(Throwable throwable) {
        open = false;
        dispatch(() -> {
            notifyError(throwable);
            processed.complete(null);
        });
    }

    private void notifyError(Throwable throwable) {
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
        dispatch(() -> {
            notifyComplete();
            processed.complete(null);
        });
    }

    private void notifyComplete() {
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
                    pendingReconnect = scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
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
     * Run a callback on the executor of the client, after the callbacks dispatched before it.
     */
    private void dispatch(Runnable callback) {
        callbacks.add(callback);
        if (pendingCallbacks.getAndIncrement() == 0) {
            try {
                executor.execute(this::drainCallbacks);
            } catch (RejectedExecutionException e) {
                // the executor is shut down: run on this thread
                drainCallbacks();
            }
        }
    }

    /**
     * @return The number of callbacks dispatched and not yet run
     */
    int pendingCallbacks() {
        return pendingCallbacks.get();
    }

    private void drainCallbacks() {
        callbackThread = Thread.currentThread();
        try {
            drainCallbacksOnThisThread();
        } finally {
            callbackThread = null;
        }
    }

    private void drainCallbacksOnThisThread() {
        do {
            Runnable callback = callbacks.poll();
            if (callback != null) {
                try {
                    callback.run();
                } catch (RuntimeException e) {
                    LOG.warn("An event source callback failed: {}", e.getMessage(), e);
                }
            }
        } while (pendingCallbacks.decrementAndGet() != 0);
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
