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
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsIOException;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.jaxrs.common.JaxRsResponse;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.RedirectionException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.CompletionStageRxInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    @NonNull
    private final URI uri;
    @Nullable
    private final String method;
    @Nullable
    private final Entity<?> entity;

    private final MutableHttpHeaders mutableHttpHeaders;

    private final JaxRsConfiguration configuration;

    // the properties of the request, which its filters see
    private final Map<String, Object> properties;

    JaxRsInvocation(JaxRsClient client,
                    @NonNull URI uri,
                    @Nullable String method,
                    @Nullable Entity<?> entity,
                    MutableHttpHeaders mutableHttpHeaders,
                    JaxRsConfiguration configuration,
                    Map<String, Object> properties) {
        this.client = client;
        this.uri = uri;
        this.method = method;
        this.entity = entity;
        this.mutableHttpHeaders = mutableHttpHeaders;
        this.configuration = configuration.copy();
        this.properties = new LinkedHashMap<>(properties);
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

    private <T> T invoke(Argument<T> type) {
        return invoke(null, type, null);
    }

    <T> T invokeExchange(Argument<T> type, @Nullable Entity<?> entity) {
        return invoke(method, type, entity);
    }

    /**
     * A synchronous invocation: the response is filtered and read on the calling thread.
     */
    private <T> T invoke(@Nullable String method, Argument<T> type, @Nullable Entity<?> entity) {
        Exchanged exchanged;
        try {
            exchanged = send(method, entity).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ProcessingException(e.getCause());
        }
        return result(exchanged, type);
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
        Runnable invocation = () -> {
            var _ = send(method, entity).whenComplete((exchanged, throwable) -> execute(executor, () -> {
                T value = null;
                Throwable failure = throwable;
                if (failure == null) {
                    try {
                        value = result(exchanged, type);
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
        Throwable cause = unwrap(failure);
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

    private static @Nullable Throwable unwrap(@Nullable Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Send a request: its request filters run first, then its entity is written, through the
     * writers and the writer interceptors (JAX-RS 6.4).
     */
    private CompletableFuture<Exchanged> send(@Nullable String method, @Nullable Entity<?> entity) {
        var future = new CompletableFuture<Exchanged>();
        try {
            Entity<?> requestEntity = entity == null ? this.entity : entity;
            MutableHttpRequest<Object> request = createRequest(method, requestEntity);
            Object entityValue = null;
            Argument<?> requestBodyType = Argument.OBJECT_ARGUMENT;
            if (requestEntity != null) {
                // a null entity is no entity
                entityValue = requestEntity.getEntity();
                if (entityValue instanceof GenericEntity<?> genericEntity) {
                    requestBodyType = JaxRsArgumentUtil.from(genericEntity, requestEntity.getAnnotations());
                    entityValue = genericEntity.getEntity();
                } else if (entityValue != null) {
                    requestBodyType = JaxRsArgumentUtil.from(requestEntity);
                }
            }
            JaxRsClientRequestContext requestContext = new JaxRsClientRequestContext(client, configuration, request,
                entityValue, requestBodyType, properties);
            for (ClientRequestFilter requestFilter : configuration.getRequestFilters()) {
                requestFilter.filter(requestContext);
                Response response = requestContext.getResponse();
                if (response != null) {
                    future.complete(new Exchanged(written(response), requestContext));
                    return future;
                }
            }
            request = changed(request, requestContext);
            // the relative links of the response are resolved against the URI of the request
            URI requestUri = request.getUri();
            client.getHttpClient().exchange(request)
                .subscribe(new Subscriber<>() {
                    @Override
                    public void onSubscribe(Subscription subscription) {
                        subscription.request(1L);
                    }

                    @Override
                    public void onNext(HttpResponse<ByteBuffer> response) {
                        future.complete(new Exchanged(new JaxRsMutableResponse(response.toMutableResponse()
                            .attribute(JaxRsResponse.REQUEST_URI, requestUri)), requestContext));
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        if (throwable instanceof HttpClientResponseException httpClientResponseException) {
                            // a response that is not successful is filtered like any other
                            HttpResponse<?> response = httpClientResponseException.getResponse();
                            future.complete(new Exchanged(new JaxRsMutableResponse(errorResponse(response)
                                .attribute(JaxRsResponse.REQUEST_URI, requestUri)), requestContext));
                        } else {
                            future.completeExceptionally(new ProcessingException(throwable));
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (!future.isDone()) {
                            future.completeExceptionally(new ProcessingException("Expected a response"));
                        }
                    }
                });
        } catch (JaxRsIOException e) {
            future.completeExceptionally(new ProcessingException(e.getCause()));
        } catch (Exception e) {
            future.completeExceptionally(new ProcessingException(e));
        }
        return future;
    }

    /**
     * A response that is not successful, with its entity: the HTTP client did not convert it, so
     * it is the bytes of the response.
     */
    @SuppressWarnings("unchecked")
    private static MutableHttpResponse<?> errorResponse(HttpResponse<?> response) {
        MutableHttpResponse<?> mutableResponse = response.toMutableResponse();
        if (mutableResponse.getBody().isEmpty()) {
            response.getBody(byte[].class).ifPresent(((MutableHttpResponse<Object>) mutableResponse)::body);
        }
        return mutableResponse;
    }

    /**
     * The result of an invocation: the response, filtered by the response filters, or its entity,
     * or the exception of its status when it is not successful (JAX-RS, {@code SyncInvoker}).
     */
    @SuppressWarnings({"unchecked", "NullAway"}) // a response may have no entity
    private <T> T result(Exchanged exchanged, Argument<T> type) {
        Response response;
        try {
            response = filterResponse(exchanged.response(), exchanged.requestContext());
        } catch (Exception e) {
            throw new ProcessingException(e);
        }
        if (type.getType().equals(Response.class)) {
            return (T) response;
        }
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            throw exception(response);
        }
        try {
            return response instanceof JaxRsResponse jaxRsResponse ? jaxRsResponse.readEntity(type) : response.readEntity(type.getType());
        } catch (Exception e) {
            throw new ProcessingException(e);
        }
    }

    /**
     * The request as the request filters changed it: its method, and its entity written through
     * the entity stream they replaced.
     */
    @SuppressWarnings("unchecked")
    private MutableHttpRequest<Object> changed(MutableHttpRequest<Object> request, JaxRsClientRequestContext requestContext) throws IOException {
        String changedMethod = requestContext.getChangedMethod();
        if (changedMethod != null && !changedMethod.equals(request.getMethodName())) {
            HttpMethod httpMethod = HttpMethod.parse(changedMethod);
            MutableHttpRequest<Object> copy = httpMethod == HttpMethod.CUSTOM
                ? HttpRequest.create(HttpMethod.CUSTOM, request.getUri().toString(), changedMethod)
                : HttpRequest.create(httpMethod, request.getUri().toString());
            request.getHeaders().forEachValue(copy::header);
            request = copy;
        }
        Object entity = requestContext.getEntity();
        if (entity != null) {
            // the entity as the filters left it, with the media type they left
            configuration.writeBody(request, requestContext.getEntityArgument(), entity, requestContext.properties());
            Object body = request.getBody().orElse(null);
            if (body instanceof byte[] bytes) {
                request.body(requestContext.writeThroughEntityStream(bytes));
            }
        }
        return request;
    }

    /**
     * The exception of a response that is not successful: the most specific one of its status
     * (JAX-RS, {@code SyncInvoker}).
     */
    static WebApplicationException exception(Response response) {
        int status = response.getStatus();
        return switch (status) {
            case 400 -> new BadRequestException(response);
            case 401 -> new NotAuthorizedException(response);
            case 403 -> new ForbiddenException(response);
            case 404 -> new NotFoundException(response);
            case 405 -> new NotAllowedException(response);
            case 406 -> new NotAcceptableException(response);
            case 415 -> new NotSupportedException(response);
            case 500 -> new InternalServerErrorException(response);
            case 503 -> new ServiceUnavailableException(response);
            default -> switch (response.getStatusInfo().getFamily()) {
                case REDIRECTION -> new RedirectionException(response);
                case CLIENT_ERROR -> new ClientErrorException(response);
                case SERVER_ERROR -> new ServerErrorException(response);
                default -> new WebApplicationException(response);
            };
        };
    }

    /**
     * The response a request filter aborted with, with its entity written by the writers of the
     * client, like the entity of a response of the server: it is read as any type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Response written(Response response) {
        if (response instanceof JaxRsMutableResponse jaxRsMutableResponse) {
            MutableHttpResponse<?> httpResponse = jaxRsMutableResponse.getResponse();
            Object entity = httpResponse.getBody().orElse(null);
            if (entity != null && !(entity instanceof byte[])) {
                configuration.writeBody(httpResponse, (Argument) Argument.of(entity.getClass()), entity);
            }
        }
        return response;
    }

    private Response filterResponse(Response response, JaxRsClientRequestContext requestContext) {
        if (response instanceof JaxRsMutableResponse jaxRsMutableResponse) {
            jaxRsMutableResponse = jaxRsMutableResponse.withEntityReader(configuration.createHttpMessageEntityReader(properties(requestContext)));
            filterResponse(jaxRsMutableResponse, requestContext);
            return jaxRsMutableResponse;
        }
        if (response instanceof JaxRsResponse jaxRsResponse) {
            MutableHttpResponse<?> mutableResponse = jaxRsResponse.getResponse().toMutableResponse();
            JaxRsMutableResponse jaxRsMutableResponse = new JaxRsMutableResponse(mutableResponse, configuration.createHttpMessageEntityReader(properties(requestContext)));
            filterResponse(jaxRsMutableResponse, requestContext);
            return jaxRsMutableResponse;
        }
        return response;
    }

    /**
     * The properties of the request, which the interceptor contexts of its response share.
     */
    private static Map<String, Object> properties(@Nullable JaxRsClientRequestContext requestContext) {
        return requestContext == null ? new LinkedHashMap<>() : requestContext.properties();
    }

    private void filterResponse(@NonNull JaxRsMutableResponse jaxRsMutableResponse, @Nullable JaxRsClientRequestContext requestContext) {
        try {
            List<ClientResponseFilter> filters = configuration.getResponseFilters();
            if (!filters.isEmpty()) {
                JaxRsClientResponseContext responseContext = new JaxRsClientResponseContext(jaxRsMutableResponse);
                for (ClientResponseFilter filter : filters) {
                    filter.filter(requestContext, responseContext);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The request, without its entity: it is written after the request filters ran.
     */
    private MutableHttpRequest<Object> createRequest(@Nullable String method, @Nullable Entity<?> entity) {
        if (method == null) {
            method = this.method;
        }
        Objects.requireNonNull(method, "method");
        // a method that is not a standard one, e.g. PURGE, is a custom one
        HttpMethod httpMethod = HttpMethod.parse(method);
        MutableHttpRequest<Object> mutableHttpRequest = httpMethod ==
            HttpMethod.CUSTOM ? HttpRequest.create(HttpMethod.CUSTOM, uri.toString(), method) : HttpRequest.create(httpMethod, uri.toString());
        if (entity != null) {
            if (entity.getMediaType() != null) {
                mutableHttpRequest = mutableHttpRequest.contentType(MediaType.of(entity.getMediaType().toString()));
            }
            Locale language = entity.getLanguage();
            if (language != null) {
                mutableHttpRequest.getHeaders().set(HttpHeaders.CONTENT_LANGUAGE, language.toLanguageTag());
            }
        }
        if (mutableHttpHeaders != null) {
            mutableHttpHeaders.forEachValue(mutableHttpRequest::header);
        }
        return mutableHttpRequest;
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

    /**
     * The response of a request, not filtered yet, with the context of the request.
     *
     * @param response       The response
     * @param requestContext The context of the request
     */
    private record Exchanged(Response response, JaxRsClientRequestContext requestContext) {
    }
}
