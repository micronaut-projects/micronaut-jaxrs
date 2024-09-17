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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
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
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.jaxrs.common.JaxRsResponse;
import jakarta.ws.rs.ProcessingException;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;

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
    @NonNull
    private final String method;
    @Nullable
    private final Entity<?> entity;

    private final MutableHttpHeaders mutableHttpHeaders;

    private final JaxRsConfiguration configuration;

    JaxRsInvocation(JaxRsClient client,
                    @NonNull URI uri,
                    String method,
                    @Nullable Entity<?> entity,
                    MutableHttpHeaders mutableHttpHeaders,
                    JaxRsConfiguration configuration) {
        this.client = client;
        this.uri = uri;
        this.method = method;
        this.entity = entity;
        this.mutableHttpHeaders = mutableHttpHeaders;
        this.configuration = configuration.copy();
    }

    @Override
    public Invocation property(String name, Object value) {
        configuration.addProperty(name, value);
        return this;
    }

    @Override
    public Response invoke() {
        return asyncBlock(async(Argument.of(Response.class)));
    }

    @Override
    public <T> T invoke(Class<T> aClass) {
        return asyncBlock(async(Argument.of(aClass)));
    }

    @Override
    public <T> T invoke(GenericType<T> genericType) {
        return invoke(JaxRsArgumentUtil.from(genericType));
    }

    @Override
    public Future<Response> submit() {
        return async(Argument.of(Response.class));
    }

    @Override
    public <T> Future<T> submit(Class<T> aClass) {
        return async(Argument.of(aClass));
    }

    @Override
    public <T> Future<T> submit(GenericType<T> genericType) {
        return async(JaxRsArgumentUtil.from(genericType));
    }

    @Override
    public <T> Future<T> submit(InvocationCallback<T> invocationCallback) {
        return async(JaxRsArgumentUtil.from(invocationCallback)).whenComplete(withCallback(invocationCallback));
    }

    private <T> T invoke(Argument<T> type) {
        return asyncBlock(async(type));
    }

    private <T> T asyncBlock(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ProcessingException(e);
        }
    }

    <T> T invokeExchange(Argument<T> type, Entity<?> entity) {
        return asyncBlock(async(method, type, entity));
    }

    private <T> CompletableFuture<T> async(Argument<T> type) {
        return async((String) null, type, null);
    }

    private <T> CompletableFuture<T> async(HttpMethod method, Argument<T> type) {
        return async(method.name(), type, null);
    }

    private <T> CompletableFuture<T> async(String method, Argument<T> type) {
        return async(method, type, null);
    }

    private <T> CompletableFuture<T> async(HttpMethod method, Argument<T> type, Entity<?> entity) {
        return async(method.name(), type, entity);
    }

    private <T> CompletableFuture<T> async(String method, Argument<T> type, Entity<?> entity) {
        var future = new CompletableFuture<T>();
        try {
            var requestBodyType = Argument.of(Object.class);
            if (entity == null) {
                entity = this.entity;
            }
            MutableHttpRequest<Object> request = createRequest(method, entity);
            if (entity != null) {
                Object entityValue = entity.getEntity();
                if (entityValue instanceof GenericEntity<?> genericEntity) {
                    requestBodyType = (Argument<Object>) JaxRsArgumentUtil.from(genericEntity);
                } else {
                    requestBodyType = (Argument<Object>) Argument.of(entityValue.getClass(), JaxRsArgumentUtil.createAnnotationMetadata(entity.getAnnotations()));
                }
            }
            List<ClientRequestFilter> requestFilters = configuration.getRequestFilters();
            JaxRsClientRequestContext requestContext = new JaxRsClientRequestContext(client, configuration, request, requestBodyType);
            if (!requestFilters.isEmpty()) {
                for (ClientRequestFilter requestFilter : requestFilters) {
                    requestFilter.filter(requestContext);
                    Response response = requestContext.getResponse();
                    if (response != null) {
                        response = filterResponse(response, requestContext);
                        if (type.getType().equals(Response.class)) {
                            future.complete((T) response);
                        } else {
                            if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                                future.complete(response.readEntity(type.getType()));
                            } else {
                                future.completeExceptionally(new WebApplicationException(response));
                            }
                        }
                        return future;
                    }
                }
            }
            client.getHttpClient().exchange(request)
                .subscribe(new Subscriber<>() {
                    @Override
                    public void onSubscribe(Subscription subscription) {
                        subscription.request(1L);
                    }

                    @Override
                    public void onNext(HttpResponse<ByteBuffer> response) {
                        try {
                            JaxRsMutableResponse jaxRsMutableResponse = filterResponse(response.toMutableResponse(), requestContext);
                            complete(jaxRsMutableResponse);
                        } catch (Exception e) {
                            future.completeExceptionally(new ProcessingException(e));
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        if (throwable instanceof HttpClientResponseException httpClientResponseException) {
                            HttpResponse<?> response = httpClientResponseException.getResponse();
                            if (isResponseReturn()) {
                                try {
                                    MutableHttpResponse<?> mutableResponse = response.toMutableResponse();
                                    JaxRsMutableResponse jaxRsMutableResponse = filterResponse(mutableResponse, requestContext);
                                    complete(jaxRsMutableResponse);
                                } catch (Exception e) {
                                    future.completeExceptionally(new ProcessingException(e));
                                }
                            } else {
                                future.completeExceptionally(new WebApplicationException(new JaxRsResponse(response)));
                            }
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

                    private void complete(JaxRsMutableResponse jaxRsMutableResponse) {
                        if (isResponseReturn()) {
                            future.complete((T) jaxRsMutableResponse);
                        } else {
                            future.complete(jaxRsMutableResponse.readEntity(type));
                        }
                    }

                    private boolean isResponseReturn() {
                        return type.getType().equals(Response.class);
                    }
                });
        } catch (Exception e) {
            future.completeExceptionally(new ProcessingException(e));
        }
        return future;
    }

    private Response filterResponse(Response response, JaxRsClientRequestContext requestContext) {
        if (response instanceof JaxRsMutableResponse jaxRsMutableResponse) {
            jaxRsMutableResponse = jaxRsMutableResponse.withEntityReader(configuration.createHttpMessageEntityReader());
            filterResponse(jaxRsMutableResponse, requestContext);
            return jaxRsMutableResponse;
        }
        if (response instanceof JaxRsResponse jaxRsResponse) {
            jaxRsResponse = jaxRsResponse.withEntityReader(configuration.createHttpMessageEntityReader());
            MutableHttpResponse<?> mutableResponse = jaxRsResponse.getResponse().toMutableResponse();
            JaxRsMutableResponse jaxRsMutableResponse = new JaxRsMutableResponse(mutableResponse, configuration.createHttpMessageEntityReader());
            filterResponse(jaxRsMutableResponse, requestContext);
            return jaxRsMutableResponse;
        }
        return response;
    }

    private JaxRsMutableResponse filterResponse(@NonNull MutableHttpResponse<?> mutableHttpResponse, @Nullable JaxRsClientRequestContext requestContext) {
        JaxRsMutableResponse response = new JaxRsMutableResponse(mutableHttpResponse, configuration.createHttpMessageEntityReader());
        filterResponse(response, requestContext);
        return response;
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

    private MutableHttpRequest<Object> createRequest(@Nullable String method, @Nullable Entity<?> entity) {
        if (method == null) {
            method = this.method;
        }
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        MutableHttpRequest<Object> mutableHttpRequest = httpMethod ==
            HttpMethod.CUSTOM ? HttpRequest.create(HttpMethod.CUSTOM, uri.toString(), method) : HttpRequest.create(httpMethod, uri.toString());
        if (entity != null) {
            mutableHttpRequest = mutableHttpRequest.contentType(MediaType.of(entity.getMediaType().toString()));
            mutableHttpRequest = mutableHttpRequest.body(entity.getEntity());
            Locale language = entity.getLanguage();
            if (language != null) {
                mutableHttpRequest.getHeaders().set(HttpHeaders.CONTENT_LANGUAGE, language.toLanguageTag());
            }
        }
        if (mutableHttpHeaders != null) {
            mutableHttpHeaders.forEachValue(mutableHttpRequest::header);
        }
        Argument<Object> bodyArgument;
        Object body;
        if (entity != null) {
            bodyArgument = (Argument<Object>) JaxRsArgumentUtil.from(entity);
            body = entity.getEntity();
        } else {
            body = mutableHttpRequest.getBody().orElse(null);
            if (body != null) {
                bodyArgument = (Argument<Object>) Argument.of(body.getClass());
            } else {
                bodyArgument = null;
            }
        }
        configuration.writeBody(mutableHttpRequest, bodyArgument, body);
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
        return async(HttpMethod.GET, JaxRsArgumentUtil.from(callback)).whenComplete(withCallback(callback));
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
        return async(HttpMethod.PUT, JaxRsArgumentUtil.from(callback), entity).whenComplete(withCallback(callback));
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
        return async(HttpMethod.POST, JaxRsArgumentUtil.from(callback), entity).whenComplete(withCallback(callback));
    }

    private <T> BiConsumer<T, Throwable> withCallback(InvocationCallback<T> callback) {
        return (response, throwable) -> {
            if (throwable != null) {
                callback.failed(throwable);
            } else {
                callback.completed(response);
            }
        };
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
        return async(HttpMethod.DELETE, JaxRsArgumentUtil.from(callback)).whenComplete(withCallback(callback));
    }

    @Override
    public CompletableFuture<Response> head() {
        return async(HttpMethod.HEAD, Argument.of(Response.class));
    }

    @Override
    public Future<Response> head(InvocationCallback<Response> callback) {
        return async(HttpMethod.HEAD, JaxRsArgumentUtil.from(callback)).whenComplete(withCallback(callback));
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
        return async(HttpMethod.OPTIONS, JaxRsArgumentUtil.from(callback)).whenComplete(withCallback(callback));
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
        return async(HttpMethod.TRACE, JaxRsArgumentUtil.from(callback)).whenComplete(withCallback(callback));
    }

    @Override
    public CompletableFuture<Response> method(String name) {
        return async(name, Argument.of(Response.class));
    }

    @Override
    public <T> CompletableFuture<T> method(String name, Class<T> responseType) {
        return async(name, Argument.of(responseType));
    }

    @Override
    public <T> CompletableFuture<T> method(String name, GenericType<T> responseType) {
        return async(name, JaxRsArgumentUtil.from(responseType));
    }

    @Override
    public <T> Future<T> method(String name, InvocationCallback<T> callback) {
        return async(name, JaxRsArgumentUtil.from(callback)).whenComplete(withCallback(callback));
    }

    @Override
    public CompletableFuture<Response> method(String name, Entity<?> entity) {
        return async(name, Argument.of(Response.class), entity);
    }

    @Override
    public <T> CompletableFuture<T> method(String name, Entity<?> entity, Class<T> responseType) {
        return async(name, Argument.of(responseType), entity);
    }

    @Override
    public <T> CompletableFuture<T> method(String name, Entity<?> entity, GenericType<T> responseType) {
        return async(name, JaxRsArgumentUtil.from(responseType), entity);
    }

    @Override
    public <T> Future<T> method(String name, Entity<?> entity, InvocationCallback<T> callback) {
        return async(name, JaxRsArgumentUtil.from(callback), entity).whenComplete(withCallback(callback));
    }
}
