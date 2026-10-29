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

import jakarta.ws.rs.sse.InboundSseEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The consumers of an event source and their execution: the callbacks run on the executor of the
 * client, one at a time and in order, and the processing ends once, with {@code onComplete} or
 * {@code onError}. The end of the processing, which closing the source waits for, is apart from
 * the end of the connection, which the source owns.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
final class JaxRsSseCallbacks {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsSseEventSource.class);

    private final List<Consumer<InboundSseEvent>> onEvent = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> onError = new CopyOnWriteArrayList<>();
    private final List<Runnable> onComplete = new CopyOnWriteArrayList<>();
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

    /**
     * @param executor The executor the callbacks run on
     */
    JaxRsSseCallbacks(Executor executor) {
        this.executor = executor;
    }

    /**
     * @param consumer A consumer of the events
     */
    void onEvent(Consumer<InboundSseEvent> consumer) {
        onEvent.add(consumer);
    }

    /**
     * @param consumer A consumer of the error that ends the processing
     */
    void onError(Consumer<Throwable> consumer) {
        onError.add(consumer);
    }

    /**
     * @param callback A callback of the completion of the processing
     */
    void onComplete(Runnable callback) {
        onComplete.add(callback);
    }

    /**
     * Wait for the callbacks to run, up to the timeout (JAX-RS, SseEventSource#close).
     *
     * @param timeout The timeout
     * @param unit    The unit of the timeout
     * @return Whether the processing completed
     */
    boolean awaitProcessed(long timeout, TimeUnit unit) {
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
     * Dispatch an event to the consumers.
     *
     * @param inbound The event
     * @return The stage completed once the consumers processed it
     */
    CompletableFuture<@Nullable Void> received(InboundSseEvent inbound) {
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
     * Hand an event to the consumers on this thread, which runs the callbacks, unless the processing ended.
     *
     * @param inbound The event
     */
    void deliver(InboundSseEvent inbound) {
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

    /**
     * End the processing with an error, after the callbacks dispatched before.
     *
     * @param throwable The error
     */
    void error(Throwable throwable) {
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

    /**
     * Complete the processing, after the callbacks dispatched before.
     */
    void complete() {
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
     * Run a callback on the executor of the client, after the callbacks dispatched before it.
     *
     * @param callback The callback
     */
    void dispatch(Runnable callback) {
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
}
