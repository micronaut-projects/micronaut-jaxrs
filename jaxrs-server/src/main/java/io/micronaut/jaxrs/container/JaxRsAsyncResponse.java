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
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpRequestWrapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ConnectionCallback;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
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
 * <p>On a server that reports the outcome of a response ({@link ServerHttpRequest#whenResponseCompleted},
 * e.g. the Netty server), the completion callbacks are called once the response was written to
 * the client, or could not be: with {@code null} if it was written, also when an exception mapper
 * answered an exception, else with the unmapped exception, the failure of writing the response, or
 * a {@link io.micronaut.http.exceptions.ConnectionClosedException} if the client went away. On
 * another server, e.g. a servlet container, they are called once the response was processed by
 * the response filters, see {@link #completed(HttpRequest)}.</p>
 *
 * <p>A {@link ConnectionCallback} is supported if the server tells when the client goes away
 * ({@link ServerHttpRequest#whenAbandoned}): it is called when the client closes the HTTP/1.1
 * connection, or resets the HTTP/2 stream, before the response was written, and the pending
 * timeout is cancelled. Else registering it registers nothing.</p>
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsAsyncResponse implements AsyncResponse {

    /**
     * The request attribute of the exception no exception mapper answered: the completion
     * callbacks receive it.
     */
    static final String UNMAPPED_EXCEPTION = "io.micronaut.jaxrs.unmappedException";

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsAsyncResponse.class);

    private final CompletableFuture<HttpResponse<?>> response = new CompletableFuture<>();
    private final Function<@Nullable Object, HttpResponse<?>> converter;
    private final Supplier<@Nullable TaskScheduler> scheduler;
    private final Supplier<@Nullable Executor> callbackExecutor;
    private final List<CompletionCallback> completionCallbacks = new ArrayList<>();
    private final List<ConnectionCallback> connectionCallbacks = new ArrayList<>();
    private @Nullable TimeoutHandler timeoutHandler;
    private @Nullable ScheduledFuture<?> timeout;
    // the generation of the timeout, a timeout of a previous one is ignored
    private long timeoutGeneration;
    // whether the timeout was set while the timeout handler ran
    private boolean timeoutUpdated;
    private State state = State.SUSPENDED;
    private boolean callbacksCalled;
    // whether the server reports the outcome of the response, see bind
    private boolean responseCompletion;
    // whether the server tells when the client goes away
    private boolean abandonment;
    private boolean abandoned;

    /**
     * @param converter Converts a resumed value to the response
     * @param scheduler        The scheduler of the timeouts
     * @param callbackExecutor The executor of the callbacks the server reports to, e.g. the
     *                         blocking executor: not its event loop, the callbacks can block
     */
    JaxRsAsyncResponse(Function<@Nullable Object, HttpResponse<?>> converter, Supplier<@Nullable TaskScheduler> scheduler,
                       Supplier<@Nullable Executor> callbackExecutor) {
        this.converter = converter;
        this.scheduler = scheduler;
        this.callbackExecutor = callbackExecutor;
    }

    /**
     * Bind the callbacks to the request received by the server, if it supports it: call before the
     * resource method, the response is not handed to the connection yet.
     *
     * @param request The request, possibly wrapped by filters
     */
    void bind(HttpRequest<?> request) {
        ServerHttpRequest<?> serverRequest = serverRequest(request);
        if (serverRequest == null) {
            return;
        }
        // a callback may run on the registering thread if the response is done already
        // the server reports on its event loop, which the callbacks do not block
        boolean completion = serverRequest.whenResponseCompleted(failure -> {
            Throwable throwable = request.getAttribute(UNMAPPED_EXCEPTION, Throwable.class).orElse(failure);
            offload(() -> completed(throwable));
        });
        synchronized (this) {
            responseCompletion = completion;
        }
        // removed by the server when the response is done
        Runnable remover = serverRequest.whenAbandoned(() -> offload(this::abandoned));
        synchronized (this) {
            abandonment |= remover != null;
        }
    }

    /**
     * Run callbacks on the callback executor, else on this thread.
     */
    private void offload(Runnable callbacks) {
        Executor executor = callbackExecutor.get();
        if (executor != null) {
            try {
                executor.execute(callbacks);
                return;
            } catch (RejectedExecutionException e) {
                // the executor is shut down: run on this thread
            }
        }
        callbacks.run();
    }

    private static @Nullable ServerHttpRequest<?> serverRequest(HttpRequest<?> request) {
        HttpRequest<?> current = request;
        while (true) {
            if (current instanceof ServerHttpRequest<?> serverRequest) {
                return serverRequest;
            }
            if (current instanceof HttpRequestWrapper<?> wrapper) {
                current = wrapper.getDelegate();
            } else {
                return null;
            }
        }
    }

    /**
     * The client went away before the response was written: cancel the timeout and call the
     * connection callbacks.
     */
    private void abandoned() {
        List<ConnectionCallback> callbacks;
        ScheduledFuture<?> scheduled;
        synchronized (this) {
            if (abandoned) {
                return;
            }
            abandoned = true;
            // the abandonment can be reported while bind registers it
            abandonment = true;
            callbacks = new ArrayList<>(connectionCallbacks);
            scheduled = timeout;
            timeout = null;
            // a timeout that is running already is ignored
            timeoutGeneration++;
        }
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        for (ConnectionCallback callback : callbacks) {
            disconnect(callback);
        }
    }

    private void disconnect(ConnectionCallback callback) {
        try {
            callback.onDisconnect(this);
        } catch (Exception e) {
            LOG.error("A JAX-RS connection callback failed", e);
        }
    }

    /**
     * @return The stage of the response
     */
    CompletionStage<HttpResponse<?>> response() {
        return response;
    }

    @Override
    public boolean resume(@Nullable Object value) {
        if (!finish(State.RESUMED)) {
            return false;
        }
        complete(value);
        return true;
    }

    @Override
    public boolean resume(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        if (!finish(State.RESUMED)) {
            return false;
        }
        response.completeExceptionally(throwable);
        return true;
    }

    private void complete(@Nullable Object value) {
        HttpResponse<?> converted;
        try {
            converted = converter.apply(value);
        } catch (Throwable e) {
            response.completeExceptionally(e);
            return;
        }
        response.complete(converted);
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
            if (state == State.CANCELLED) {
                return true;
            }
        }
        if (!finish(State.CANCELLED)) {
            // resumed, or cancelled concurrently
            return isCancelled();
        }
        complete(builder.build());
        return true;
    }

    /**
     * Resume or cancel the response: only one of them wins.
     *
     * @param finished The state of the response
     * @return Whether the response was suspended
     */
    private boolean finish(State finished) {
        ScheduledFuture<?> scheduled;
        synchronized (this) {
            if (state != State.SUSPENDED) {
                return false;
            }
            state = finished;
            scheduled = timeout;
            timeout = null;
        }
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        return true;
    }

    /**
     * Call the completion callbacks of the asynchronous response of a request once its response
     * was processed: after the exception mappers and the response filters. Only if the server does
     * not report the outcome of the response, else they are called once it was written.
     *
     * @param request The request
     */
    static void completed(HttpRequest<?> request) {
        JaxRsAsyncResponse asyncResponse = request.getAttribute(JaxRsRuntimeRoutes.ASYNC_RESPONSE, JaxRsAsyncResponse.class).orElse(null);
        if (asyncResponse != null && !asyncResponse.reportsCompletion()) {
            // a mapped exception completes with a response: the callbacks receive no exception
            asyncResponse.completed(request.getAttribute(UNMAPPED_EXCEPTION, Throwable.class).orElse(null));
        }
    }

    private synchronized boolean reportsCompletion() {
        return responseCompletion;
    }

    private void completed(@Nullable Throwable throwable) {
        List<CompletionCallback> callbacks;
        synchronized (this) {
            // reported by the server, the response is also done if the client went away while suspended
            if ((state == State.SUSPENDED && !responseCompletion) || callbacksCalled) {
                return;
            }
            callbacksCalled = true;
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
        return state == State.SUSPENDED;
    }

    @Override
    public synchronized boolean isCancelled() {
        return state == State.CANCELLED;
    }

    @Override
    public synchronized boolean isDone() {
        return state != State.SUSPENDED;
    }

    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        ScheduledFuture<?> previous;
        synchronized (this) {
            if (state != State.SUSPENDED) {
                return false;
            }
            previous = timeout;
            timeout = null;
            timeoutUpdated = true;
            long generation = ++timeoutGeneration;
            if (time > 0) {
                TaskScheduler taskScheduler = scheduler.get();
                if (taskScheduler == null) {
                    throw new IllegalStateException("No task scheduler to time out the asynchronous response");
                }
                timeout = taskScheduler.schedule(Duration.ofNanos(unit.toNanos(time)), () -> timedOut(generation));
            }
            // a time of zero or less suspends the response indefinitely
        }
        if (previous != null) {
            previous.cancel(false);
        }
        return true;
    }

    private void timedOut(long generation) {
        TimeoutHandler handler;
        synchronized (this) {
            if (state != State.SUSPENDED || generation != timeoutGeneration) {
                // resumed, or the timeout was set again
                return;
            }
            timeout = null;
            timeoutUpdated = false;
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
                // the handler resumed, cancelled or set the timeout again, also to no timeout
                if (state != State.SUSPENDED || timeoutUpdated) {
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
        if (!CompletionCallback.class.isAssignableFrom(callback)
            && !(ConnectionCallback.class.isAssignableFrom(callback) && supportsConnectionCallback())) {
            // a connection callback is not supported without the abandonment of the server
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
        List<Class<?>> registered = new ArrayList<>(2);
        if (callback instanceof CompletionCallback completionCallback) {
            synchronized (this) {
                completionCallbacks.add(completionCallback);
            }
            registered.add(CompletionCallback.class);
        }
        if (callback instanceof ConnectionCallback connectionCallback) {
            boolean disconnected;
            synchronized (this) {
                if (abandonment) {
                    connectionCallbacks.add(connectionCallback);
                    registered.add(ConnectionCallback.class);
                }
                disconnected = abandonment && abandoned;
            }
            if (disconnected) {
                // the client went away already
                disconnect(connectionCallback);
            }
        }
        return registered;
    }

    private synchronized boolean supportsConnectionCallback() {
        return abandonment;
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

    /**
     * The state of an asynchronous response.
     */
    private enum State {
        SUSPENDED,
        RESUMED,
        CANCELLED
    }
}
