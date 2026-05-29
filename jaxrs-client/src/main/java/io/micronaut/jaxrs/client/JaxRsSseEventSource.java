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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.sse.SseClient;
import io.micronaut.http.sse.Event;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Micronaut-backed {@link SseEventSource}.
 */
@Internal
final class JaxRsSseEventSource implements SseEventSource {
    static final long DEFAULT_RECONNECT_DELAY_MILLIS = 500L;

    private final WebTarget target;
    private final ScheduledExecutorService scheduler;
    private final boolean closeScheduler;
    private final boolean closeSseClient;
    private final List<EventConsumer> consumers = new ArrayList<>();
    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile long reconnectDelayMillis;
    private Subscription subscription;
    private ScheduledFuture<?> reconnectTask;
    private @Nullable SseClient sseClient;

    JaxRsSseEventSource(WebTarget target, long reconnectDelayMillis) {
        this(target, reconnectDelayMillis, null, true, null, true);
    }

    JaxRsSseEventSource(WebTarget target,
                        long reconnectDelayMillis,
                        @Nullable ScheduledExecutorService scheduler,
                        boolean closeScheduler,
                        @Nullable SseClient sseClient,
                        boolean closeSseClient) {
        this.target = target;
        this.reconnectDelayMillis = reconnectDelayMillis;
        this.scheduler = scheduler == null ? defaultScheduler() : scheduler;
        this.closeScheduler = scheduler == null || closeScheduler;
        this.sseClient = sseClient;
        this.closeSseClient = closeSseClient;
    }

    @Override
    public synchronized void register(Consumer<InboundSseEvent> onEvent) {
        register(onEvent, ignored -> {
        }, () -> {
        });
    }

    @Override
    public synchronized void register(Consumer<InboundSseEvent> onEvent, Consumer<Throwable> onError) {
        register(onEvent, onError, () -> {
        });
    }

    @Override
    public synchronized void register(Consumer<InboundSseEvent> onEvent, Consumer<Throwable> onError, Runnable onComplete) {
        consumers.add(new EventConsumer(onEvent, onError, onComplete));
    }

    @Override
    public synchronized void open() {
        if (!open.compareAndSet(false, true)) {
            return;
        }
        connect();
    }

    private void connect() {
        if (closeRequested.get()) {
            return;
        }
        SseClient resolvedClient = sseClient();
        HttpRequest<?> request = HttpRequest.GET(target.getUri().toString())
            .accept(MediaType.TEXT_EVENT_STREAM_TYPE);
        resolvedClient.eventStream(request).subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                if (closeRequested.get()) {
                    subscription.cancel();
                    return;
                }
                JaxRsSseEventSource.this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Event<ByteBuffer<?>> event) {
                InboundSseEvent inboundEvent = new JaxRsInboundSseEvent(Event.of(event, event.getData().toString(StandardCharsets.UTF_8)));
                if (inboundEvent.isReconnectDelaySet()) {
                    reconnectDelayMillis = inboundEvent.getReconnectDelay();
                }
                for (EventConsumer consumer : List.copyOf(consumers)) {
                    consumer.onEvent().accept(inboundEvent);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (closeRequested.get()) {
                    complete();
                    return;
                }
                for (EventConsumer consumer : List.copyOf(consumers)) {
                    consumer.onError().accept(throwable);
                }
                if (isRecoverable(throwable)) {
                    scheduleReconnect(reconnectDelayMillis(throwable));
                } else {
                    complete();
                }
            }

            @Override
            public void onComplete() {
                if (closeRequested.get()) {
                    complete();
                    return;
                }
                scheduleReconnect(reconnectDelayMillis);
            }
        });
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public boolean close(long timeout, TimeUnit unit) {
        closeRequested.set(true);
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(true);
        }
        Subscription resolvedSubscription = subscription;
        if (resolvedSubscription != null) {
            resolvedSubscription.cancel();
        }
        complete();
        try {
            return closed.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private synchronized void complete() {
        if (open.getAndSet(false)) {
            closeResources();
        } else if (closeRequested.get()) {
            closeResources();
        }
    }

    private void closeResources() {
        if (resourcesClosed.compareAndSet(false, true)) {
            if (closeScheduler) {
                scheduler.shutdownNow();
            }
            if (closeSseClient && sseClient instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Closing should not mask the SseEventSource close result.
                }
            }
            closed.countDown();
        }
    }

    private synchronized void scheduleReconnect(long delayMillis) {
        if (closeRequested.get()) {
            return;
        }
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
        }
        reconnectTask = scheduler.schedule(this::connect, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private static boolean isRecoverable(Throwable throwable) {
        return !(throwable instanceof HttpClientResponseException exception) || exception.code() == 503;
    }

    private long reconnectDelayMillis(Throwable throwable) {
        if (throwable instanceof HttpClientResponseException exception && exception.code() == 503) {
            Integer retryAfterSeconds = exception.getResponse().getHeaders().getInt(HttpHeaders.RETRY_AFTER);
            if (retryAfterSeconds != null) {
                return TimeUnit.SECONDS.toMillis(Math.max(0, retryAfterSeconds));
            }
            ZonedDateTime retryAfter = exception.getResponse().getHeaders().getDate(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                return Math.max(0L, Duration.between(ZonedDateTime.now(retryAfter.getZone()), retryAfter).toMillis());
            }
        }
        return reconnectDelayMillis;
    }

    private synchronized SseClient sseClient() {
        if (sseClient == null) {
            URI uri = target.getUri();
            try {
                sseClient = SseClient.create(uri.toURL());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid SSE target URI: " + uri, e);
            }
        }
        return sseClient;
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "jaxrs-sse-event-source");
            thread.setDaemon(true);
            return thread;
        });
    }

    private record EventConsumer(Consumer<InboundSseEvent> onEvent,
                                 Consumer<Throwable> onError,
                                 Runnable onComplete) {
    }
}
