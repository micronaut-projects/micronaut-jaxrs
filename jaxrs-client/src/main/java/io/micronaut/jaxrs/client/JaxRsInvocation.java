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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.jaxrs.client.JaxRsClientExchange.Exchanged;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.CompletionStageRxInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * The implementation of {@link Invocation}, {@link CompletionStageRxInvoker} and {@link AsyncInvoker}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsInvocation implements Invocation, CompletionStageRxInvoker, AsyncInvoker {

    @NonNull
    private final JaxRsClient client;
    @Nullable
    private final String method;
    @Nullable
    private final Entity<?> entity;

    private final MutableHttpHeaders mutableHttpHeaders;

    private final JaxRsConfiguration configuration;

    // the properties of the request, which its filters see
    private final Map<String, Object> properties;
    private final JaxRsClientExchange exchange;

    JaxRsInvocation(JaxRsClient client,
                    @NonNull URI uri,
                    @Nullable String method,
                    @Nullable Entity<?> entity,
                    MutableHttpHeaders mutableHttpHeaders,
                    JaxRsConfiguration configuration,
                    Map<String, Object> properties) {
        this.client = client;
        this.method = method;
        this.entity = entity;
        // a snapshot: later changes of the builder don't change a built invocation
        this.mutableHttpHeaders = new SimpleHttpHeaders(ConversionService.SHARED);
        mutableHttpHeaders.forEachValue(this.mutableHttpHeaders::add);
        this.configuration = configuration.copy();
        this.properties = new LinkedHashMap<>(properties);
        this.exchange = new JaxRsClientExchange(client, uri, this.mutableHttpHeaders, this.configuration, this.properties);
    }

    @Override
    public Invocation property(String name, @Nullable Object value) {
        // a null value removes the property
        if (value == null) {
            properties.remove(name);
        } else {
            properties.put(name, value);
        }
        return this;
    }

    @Override
    public Response invoke() {
        return invoke(Argument.of(Response.class));
    }

    @Override
    public <T> T invoke(Class<T> aClass) {
        return invoke(Argument.of(aClass));
    }

    @Override
    public <T> T invoke(GenericType<T> genericType) {
        return invoke(JaxRsArgumentUtil.from(genericType));
    }

    @Override
    public Future<Response> submit() {
        return async(null, Argument.of(Response.class), null, null);
    }

    @Override
    public <T> Future<T> submit(Class<T> aClass) {
        return async(null, Argument.of(aClass), null, null);
    }

    @Override
    public <T> Future<T> submit(GenericType<T> genericType) {
        return async(null, JaxRsArgumentUtil.from(genericType), null, null);
    }

    @Override
    public <T> Future<T> submit(InvocationCallback<T> invocationCallback) {
        return async(null, JaxRsArgumentUtil.from(invocationCallback), null, invocationCallback);
    }

    /**
     * A synchronous invocation: the response is filtered and read on the calling thread.
     */
    private <T> T invoke(Argument<T> type) {
        Exchanged exchanged;
        try {
            exchanged = exchange.send(method, entity).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ProcessingException(e.getCause());
        }
        return exchange.result(exchanged, type);
    }

    private <T> CompletableFuture<T> async(HttpMethod method, Argument<T> type) {
        return async(method.name(), type, null, null);
    }

    private <T> CompletableFuture<T> async(HttpMethod method, Argument<T> type, @Nullable Entity<?> entity) {
        return async(method.name(), type, entity, null);
    }

    /**
     * An asynchronous invocation: it runs, from its request filters on, on the executor service of
     * the client (JAX-RS, {@code ClientBuilder#executorService}), and so do its response filters,
     * the reading of its entity, its callback and the continuations of the returned stage, never
     * on the event loop of the HTTP client. The future fails with the exception itself, not with
     * a {@link java.util.concurrent.CompletionException}.
     */
    private <T> CompletableFuture<T> async(@Nullable String method, Argument<T> type, @Nullable Entity<?> entity,
                                           @Nullable InvocationCallback<T> callback) {
        var result = new CompletableFuture<T>();
        Executor executor = client.getExecutorService();
        // the exchange in progress, cancelled with the future (JAX-RS, AsyncInvoker; Future#cancel)
        var sending = new AtomicReference<@Nullable CompletableFuture<Exchanged>>();
        result.whenComplete((ignored, failure) -> {
            CompletableFuture<Exchanged> sent = sending.get();
            if (result.isCancelled() && sent != null) {
                sent.cancel(false);
            }
        });
        Runnable invocation = () -> {
            if (result.isDone()) {
                // cancelled before it ran: it is not sent
                return;
            }
            CompletableFuture<Exchanged> sent = exchange.send(method == null ? this.method : method, entity == null ? this.entity : entity);
            sending.set(sent);
            if (result.isCancelled()) {
                sent.cancel(false);
                return;
            }
            var _ = sent.whenComplete((exchanged, throwable) -> execute(executor, () -> {
                if (result.isDone()) {
                    // cancelled
                    return;
                }
                T value = null;
                Throwable failure = throwable;
                if (failure == null) {
                    try {
                        value = exchange.result(exchanged, type);
                    } catch (RuntimeException e) {
                        failure = e;
                    }
                }
                complete(result, value, failure, callback);
            }, failure -> complete(result, null, failure, callback)));
        };
        execute(executor, invocation, failure -> complete(result, null, failure, callback));
        return result;
    }

    private static void execute(Executor executor, Runnable task, Consumer<Throwable> rejected) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            rejected.accept(new ProcessingException(e));
        }
    }

    /**
     * Complete an asynchronous invocation: its callback, then its future.
     */
    private static <T> void complete(CompletableFuture<T> result, @Nullable T value, @Nullable Throwable failure,
                                     @Nullable InvocationCallback<T> callback) {
        Throwable cause = JaxRsClientExchange.unwrap(failure);
        try {
            if (callback != null) {
                if (cause == null) {
                    callback.completed(value);
                } else {
                    callback.failed(cause);
                }
            }
        } finally {
            if (cause == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(cause);
            }
        }
    }

    @Override
    public CompletableFuture<Response> get() {
        return async(HttpMethod.GET, Argument.of(Response.class));
    }

    @Override
    public <T> CompletableFuture<T> get(Class<T> responseType) {
        return async(HttpMethod.GET, Argument.of(responseType));
    }

    @Override
    public <T> CompletableFuture<T> get(GenericType<T> responseType) {
        return async(HttpMethod.GET, JaxRsArgumentUtil.from(responseType));
    }

    @Override
    public <T> Future<T> get(InvocationCallback<T> callback) {
        return asyncCallback(HttpMethod.GET, JaxRsArgumentUtil.from(callback), callback);
    }

    @Override
    public CompletableFuture<Response> put(Entity<?> entity) {
        return async(HttpMethod.PUT, Argument.of(Response.class), entity);
    }

    @Override
    public <T> CompletableFuture<T> put(Entity<?> entity, Class<T> clazz) {
        return async(HttpMethod.PUT, Argument.of(clazz), entity);
    }

    @Override
    public <T> CompletableFuture<T> put(Entity<?> entity, GenericType<T> type) {
        return async(HttpMethod.PUT, JaxRsArgumentUtil.from(type), entity);
    }

    @Override
    public <T> Future<T> put(Entity<?> entity, InvocationCallback<T> callback) {
        return asyncCallback(HttpMethod.PUT, JaxRsArgumentUtil.from(callback), entity, callback);
    }

    @Override
    public CompletableFuture<Response> post(Entity<?> entity) {
        return async(HttpMethod.POST, Argument.of(Response.class), entity);
    }

    @Override
    public <T> CompletableFuture<T> post(Entity<?> entity, Class<T> clazz) {
        return async(HttpMethod.POST, Argument.of(clazz), entity);
    }

    @Override
    public <T> CompletableFuture<T> post(Entity<?> entity, GenericType<T> type) {
        return async(HttpMethod.POST, JaxRsArgumentUtil.from(type), entity);
    }

    @Override
    public <T> Future<T> post(Entity<?> entity, InvocationCallback<T> callback) {
        return asyncCallback(HttpMethod.POST, JaxRsArgumentUtil.from(callback), entity, callback);
    }

    private <T> Future<T> asyncCallback(HttpMethod method, Argument<T> type, InvocationCallback<T> callback) {
        return async(method.name(), type, null, callback);
    }

    private <T> Future<T> asyncCallback(HttpMethod method, Argument<T> type, @Nullable Entity<?> entity, InvocationCallback<T> callback) {
        return async(method.name(), type, entity, callback);
    }

    private <T> Future<T> asyncCallback(String method, Argument<T> type, InvocationCallback<T> callback) {
        return async(method, type, null, callback);
    }

    private <T> Future<T> asyncCallback(String method, Argument<T> type, @Nullable Entity<?> entity, InvocationCallback<T> callback) {
        return async(method, type, entity, callback);
    }

    @Override
    public CompletableFuture<Response> delete() {
        return async(HttpMethod.DELETE, Argument.of(Response.class), entity);
    }

    @Override
    public <T> CompletableFuture<T> delete(Class<T> clazz) {
        return async(HttpMethod.DELETE, Argument.of(clazz));
    }

    @Override
    public <T> CompletableFuture<T> delete(GenericType<T> type) {
        return async(HttpMethod.DELETE, JaxRsArgumentUtil.from(type));
    }

    @Override
    public <T> Future<T> delete(InvocationCallback<T> callback) {
        return asyncCallback(HttpMethod.DELETE, JaxRsArgumentUtil.from(callback), callback);
    }

    @Override
    public CompletableFuture<Response> head() {
        return async(HttpMethod.HEAD, Argument.of(Response.class));
    }

    @Override
    public Future<Response> head(InvocationCallback<Response> callback) {
        return asyncCallback(HttpMethod.HEAD, JaxRsArgumentUtil.from(callback), callback);
    }

    @Override
    public CompletableFuture<Response> options() {
        return async(HttpMethod.OPTIONS, Argument.of(Response.class));
    }

    @Override
    public <T> CompletableFuture<T> options(Class<T> responseType) {
        return async(HttpMethod.OPTIONS, Argument.of(responseType));
    }

    @Override
    public <T> CompletableFuture<T> options(GenericType<T> responseType) {
        return async(HttpMethod.OPTIONS, JaxRsArgumentUtil.from(responseType));
    }

    @Override
    public <T> Future<T> options(InvocationCallback<T> callback) {
        return asyncCallback(HttpMethod.OPTIONS, JaxRsArgumentUtil.from(callback), callback);
    }

    @Override
    public CompletableFuture<Response> trace() {
        return async(HttpMethod.TRACE, Argument.of(Response.class));
    }

    @Override
    public <T> CompletableFuture<T> trace(Class<T> responseType) {
        return async(HttpMethod.TRACE, Argument.of(responseType));
    }

    @Override
    public <T> CompletableFuture<T> trace(GenericType<T> responseType) {
        return async(HttpMethod.TRACE, JaxRsArgumentUtil.from(responseType));
    }

    @Override
    public <T> Future<T> trace(InvocationCallback<T> callback) {
        return asyncCallback(HttpMethod.TRACE, JaxRsArgumentUtil.from(callback), callback);
    }

    @Override
    public CompletableFuture<Response> method(String name) {
        return async(name, Argument.of(Response.class), null, null);
    }

    @Override
    public <T> CompletableFuture<T> method(String name, Class<T> responseType) {
        return async(name, Argument.of(responseType), null, null);
    }

    @Override
    public <T> CompletableFuture<T> method(String name, GenericType<T> responseType) {
        return async(name, JaxRsArgumentUtil.from(responseType), null, null);
    }

    @Override
    public <T> Future<T> method(String name, InvocationCallback<T> callback) {
        return asyncCallback(name, JaxRsArgumentUtil.from(callback), callback);
    }

    @Override
    public CompletableFuture<Response> method(String name, Entity<?> entity) {
        return async(name, Argument.of(Response.class), entity, null);
    }

    @Override
    public <T> CompletableFuture<T> method(String name, Entity<?> entity, Class<T> responseType) {
        return async(name, Argument.of(responseType), entity, null);
    }

    @Override
    public <T> CompletableFuture<T> method(String name, Entity<?> entity, GenericType<T> responseType) {
        return async(name, JaxRsArgumentUtil.from(responseType), entity, null);
    }

    @Override
    public <T> Future<T> method(String name, Entity<?> entity, InvocationCallback<T> callback) {
        return asyncCallback(name, JaxRsArgumentUtil.from(callback), entity, callback);
    }
}
