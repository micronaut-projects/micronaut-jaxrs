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
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.http.HttpResponse;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.TimeoutHandler;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@link AsyncResponse} of a resource method with a {@code @Suspended} parameter (JAX-RS 8.2),
 * backed by the stage of the response of its asynchronous route. A resumed value is converted to the
 * response like the result of a resource method, a resumed exception fails the stage, which the
 * exception handlers answer like an exception a resource method throws.
 *
 * <p>{@link jakarta.ws.rs.container.ConnectionCallback} is not supported: the routes are not
 * notified when the client closes the connection.</p>
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsAsyncResponse implements AsyncResponse {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsAsyncResponse.class);

    private final CompletableFuture<HttpResponse<?>> response = new CompletableFuture<>();
    private final Function<@Nullable Object, HttpResponse<?>> converter;
    private final Supplier<@Nullable TaskScheduler> scheduler;
    private final List<CompletionCallback> completionCallbacks = new ArrayList<>();
    private @Nullable TimeoutHandler timeoutHandler;
    private @Nullable ScheduledFuture<?> timeout;
    private boolean done;
    private boolean cancelled;

    /**
     * @param converter Converts a resumed value to the response
     * @param scheduler The scheduler of the timeouts
     */
    JaxRsAsyncResponse(Function<@Nullable Object, HttpResponse<?>> converter, Supplier<@Nullable TaskScheduler> scheduler) {
        this.converter = converter;
        this.scheduler = scheduler;
    }

    /**
     * @return The stage of the response
     */
    CompletionStage<HttpResponse<?>> response() {
        return response;
    }

    @Override
    public boolean resume(@Nullable Object value) {
        if (!finish()) {
            return false;
        }
        HttpResponse<?> converted;
        try {
            converted = converter.apply(value);
        } catch (Throwable e) {
            fail(e);
            return true;
        }
        response.complete(converted);
        completed(null);
        return true;
    }

    @Override
    public boolean resume(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        if (!finish()) {
            return false;
        }
        fail(throwable);
        return true;
    }

    private void fail(Throwable throwable) {
        response.completeExceptionally(throwable);
        // the exception mappers answer it, which is not known here: it is passed to the callbacks
        completed(throwable);
    }

    @Override
    public boolean cancel() {
        return cancel(Response.status(Response.Status.SERVICE_UNAVAILABLE));
    }

    @Override
    public boolean cancel(int retryAfter) {
        return cancel(Response.status(Response.Status.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, retryAfter));
    }

    @Override
    public boolean cancel(Date retryAfter) {
        return cancel(Response.status(Response.Status.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, retryAfter));
    }

    private boolean cancel(Response.ResponseBuilder builder) {
        synchronized (this) {
            if (cancelled) {
                return true;
            }
            if (done) {
                return false;
            }
            cancelled = true;
        }
        if (!finish()) {
            return true;
        }
        response.complete(converter.apply(builder.build()));
        completed(null);
        return true;
    }

    /**
     * Mark the response done.
     *
     * @return Whether it was not done
     */
    private boolean finish() {
        ScheduledFuture<?> scheduled;
        synchronized (this) {
            if (done) {
                return false;
            }
            done = true;
            scheduled = timeout;
            timeout = null;
        }
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        return true;
    }

    private void completed(@Nullable Throwable throwable) {
        List<CompletionCallback> callbacks;
        synchronized (this) {
            callbacks = new ArrayList<>(completionCallbacks);
        }
        for (CompletionCallback callback : callbacks) {
            try {
                callback.onComplete(throwable);
            } catch (Exception e) {
                LOG.error("A JAX-RS completion callback failed", e);
            }
        }
    }

    @Override
    public synchronized boolean isSuspended() {
        return !done;
    }

    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    @Override
    public synchronized boolean isDone() {
        return done;
    }

    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        ScheduledFuture<?> previous;
        synchronized (this) {
            if (done) {
                return false;
            }
            previous = timeout;
            timeout = null;
            if (time > 0) {
                TaskScheduler taskScheduler = scheduler.get();
                if (taskScheduler == null) {
                    throw new IllegalStateException("No task scheduler to time out the asynchronous response");
                }
                timeout = taskScheduler.schedule(Duration.ofNanos(unit.toNanos(time)), this::timedOut);
            }
            // a time of zero or less suspends the response indefinitely
        }
        if (previous != null) {
            previous.cancel(false);
        }
        return true;
    }

    private void timedOut() {
        TimeoutHandler handler;
        synchronized (this) {
            if (done) {
                return;
            }
            timeout = null;
            handler = timeoutHandler;
        }
        if (handler != null) {
            try {
                handler.handleTimeout(this);
            } catch (Throwable e) {
                resume(e);
                return;
            }
            synchronized (this) {
                // the handler resumed, cancelled or extended the timeout
                if (done || timeout != null) {
                    return;
                }
            }
        }
        // JAX-RS 8.2.1: the request fails with a 503
        resume(new ServiceUnavailableException());
    }

    @Override
    public synchronized void setTimeoutHandler(TimeoutHandler handler) {
        timeoutHandler = handler;
    }

    @Override
    public Collection<Class<?>> register(Class<?> callback) {
        Objects.requireNonNull(callback, "callback");
        if (!CompletionCallback.class.isAssignableFrom(callback)) {
            // a connection callback is not supported
            return List.of();
        }
        BeanIntrospection<?> introspection = JaxRsReflection.get().introspection(callback);
        if (introspection == null) {
            throw new IllegalArgumentException("Cannot create the callback " + callback.getName());
        }
        return register(introspection.instantiate());
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
        Map<Class<?>, Collection<Class<?>>> registered = new HashMap<>();
        registered.put(callback, register(callback));
        for (Class<?> other : callbacks) {
            registered.put(other, register(other));
        }
        return registered;
    }

    @Override
    public Collection<Class<?>> register(Object callback) {
        Objects.requireNonNull(callback, "callback");
        if (callback instanceof CompletionCallback completionCallback) {
            synchronized (this) {
                completionCallbacks.add(completionCallback);
            }
            return List.of(CompletionCallback.class);
        }
        // a connection callback is not supported
        return List.of();
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
        Map<Class<?>, Collection<Class<?>>> registered = new HashMap<>();
        registered.put(callback.getClass(), register(callback));
        for (Object other : callbacks) {
            registered.put(other.getClass(), register(other));
        }
        return registered;
    }
}
