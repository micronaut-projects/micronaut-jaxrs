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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseFactory;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.body.BodyElements;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.exceptions.ReadTimeoutException;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsHeaderUtil;
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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.ResponseProcessingException;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * The exchange of an invocation: the request filters, the writing of the request entity, the
 * transport, then the response filters and the reading of the response entity (JAX-RS 6.4), apart
 * from the API and the threading of the invocation.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
final class JaxRsClientExchange {

    // the attribute of a received response: the stream of its body as it arrives
    private static final String RECEIVED_ENTITY_STREAM = "io.micronaut.jaxrs.client.receivedEntityStream";

    private final JaxRsClient client;
    private final URI uri;
    private final MutableHttpHeaders mutableHttpHeaders;
    private final JaxRsConfiguration configuration;
    // the properties of the request, which its filters see: the ones of the invocation
    private final Map<String, Object> properties;

    /**
     * @param client             The client
     * @param uri                The URI of the request
     * @param mutableHttpHeaders The headers of the request
     * @param configuration      The configuration of the invocation
     * @param properties         The properties of the invocation, as they are when it is sent
     */
    JaxRsClientExchange(JaxRsClient client, URI uri, MutableHttpHeaders mutableHttpHeaders,
                        JaxRsConfiguration configuration, Map<String, Object> properties) {
        this.client = client;
        this.uri = uri;
        this.mutableHttpHeaders = mutableHttpHeaders;
        this.configuration = configuration;
        this.properties = properties;
    }

    /**
     * Send a request: its request filters run first, then its entity is written, through the
     * writers and the writer interceptors (JAX-RS 6.4).
     *
     * @param method        The method of the request
     * @param requestEntity The entity of the request, {@code null} if none
     * @return The stage of the response, not filtered yet; cancelling it cancels the exchange
     */
    CompletableFuture<Exchanged> send(@Nullable String method, @Nullable Entity<?> requestEntity) {
        var future = new CompletableFuture<Exchanged>();
        JaxRsClientRequestContext requestContext = null;
        try {
            MutableHttpRequest<Object> request = createRequest(method, requestEntity);
            Object entityValue = null;
            Argument<?> requestBodyType = Argument.OBJECT_ARGUMENT;
            if (requestEntity != null) {
                // a null entity is no entity
                entityValue = requestEntity.getEntity();
                if (entityValue instanceof GenericEntity<?> genericEntity) {
                    requestBodyType = JaxRsClientEntityIO.argument(genericEntity,
                        JaxRsArgumentUtil.from(genericEntity, requestEntity.getAnnotations()).getAnnotationMetadata());
                    entityValue = genericEntity.getEntity();
                } else if (entityValue != null) {
                    requestBodyType = JaxRsArgumentUtil.from(requestEntity);
                }
            }
            requestContext = new JaxRsClientRequestContext(client, configuration, request,
                entityValue, requestBodyType, properties);
            // the injected configuration of a shared filter is the one of this invocation (JAX-RS 10.2.8)
            Response aborted = JaxRsClientFilters.filterRequest(configuration, requestContext);
            if (aborted != null) {
                requestContext.closeReplacedEntityStream(null);
                future.complete(new Exchanged(written(aborted), requestContext));
                return future;
            }
            request = changed(configuration, request, requestContext);
            // the relative links of the response are resolved against the URI of the request
            URI requestUri = request.getUri();
            JaxRsClientRequestContext sentContext = requestContext;
            // the body is received as it arrives: it is not buffered unless an entity is read of it
            CompletableFuture<HttpResponse<BodyElements<ByteBuffer<?>>>> exchange = client.httpClient().toAsyncStreaming()
                .exchangeStream(request).toCompletableFuture();
            // the exchange of a cancelled invocation is cancelled: core cancels the transport with
            // its own future, not with a dependent stage
            future.whenComplete((ignored, failure) -> {
                if (future.isCancelled()) {
                    exchange.cancel(false);
                }
            });
            var _ = exchange.whenComplete((response, error) -> {
                Throwable throwable = unwrap(error);
                if (throwable instanceof HttpClientResponseException httpClientResponseException) {
                    // a response that is not successful is filtered like any other
                    future.complete(new Exchanged(new JaxRsMutableResponse(httpClientResponseException.getResponse().toMutableResponse()
                        .attribute(JaxRsResponse.REQUEST_URI, requestUri)), sentContext));
                } else if (throwable != null) {
                    future.completeExceptionally(failure(throwable));
                } else if (response == null) {
                    future.completeExceptionally(new ProcessingException("Expected a response"));
                } else {
                    received(response, requestUri, sentContext, future);
                }
            });
        } catch (JaxRsIOException e) {
            closeReplacedEntityStream(requestContext, e);
            future.completeExceptionally(new ProcessingException(e.getCause()));
        } catch (Exception e) {
            closeReplacedEntityStream(requestContext, e);
            future.completeExceptionally(new ProcessingException(e));
        }
        return future;
    }

    /**
     * Complete an exchange with a received response once its first bytes arrive: an empty body is
     * no entity, else the entity is the stream of the body as it arrives, which closing the
     * response releases.
     */
    private static void received(HttpResponse<BodyElements<ByteBuffer<?>>> response, URI requestUri,
                                 JaxRsClientRequestContext requestContext, CompletableFuture<Exchanged> future) {
        MutableHttpResponse<Object> head = HttpResponseFactory.INSTANCE.status(response.code(), response.reason());
        response.getHeaders().forEachValue(head.getHeaders()::add);
        head.attribute(JaxRsResponse.REQUEST_URI, requestUri);
        BodyElements<ByteBuffer<?>> body = response.body();
        if (body == null) {
            future.complete(new Exchanged(new JaxRsMutableResponse(head), requestContext));
            return;
        }
        var _ = body.next().whenComplete((first, error) -> {
            if (error != null) {
                var _ = body.closeAsync();
                future.completeExceptionally(failure(unwrap(error)));
            } else if (first.isEmpty()) {
                future.complete(new Exchanged(new JaxRsMutableResponse(head), requestContext));
            } else {
                var entityStream = new JaxRsBodyInputStream(first.get().toByteArray(), body);
                // the entity stream of the response the filters see
                head.setAttribute(RECEIVED_ENTITY_STREAM, entityStream);
                if (!future.complete(new Exchanged(new JaxRsMutableResponse(head), requestContext))) {
                    // the invocation was cancelled
                    entityStream.close();
                }
            }
        });
    }

    /**
     * The failure of a transport: the cause of a timeout is a {@link TimeoutException} (JAX-RS,
     * {@code ClientBuilder#readTimeout}).
     */
    private static ProcessingException failure(@Nullable Throwable throwable) {
        if (throwable instanceof ReadTimeoutException) {
            var timeout = new TimeoutException(throwable.getMessage());
            timeout.initCause(throwable);
            return new ProcessingException(timeout);
        }
        return new ProcessingException(throwable);
    }

    /**
     * Close the entity stream a request filter replaced when the request fails before it is sent,
     * e.g. because a later filter failed: the exchange owns it (JAX-RS, ClientRequestContext).
     */
    private static void closeReplacedEntityStream(@Nullable JaxRsClientRequestContext requestContext, Exception failure) {
        if (requestContext != null) {
            requestContext.closeReplacedEntityStream(failure);
        }
    }

    /**
     * The result of an invocation: the response, filtered by the response filters, or its entity,
     * or the exception of its status when it is not successful (JAX-RS, {@code SyncInvoker}).
     */
    @SuppressWarnings({"unchecked", "NullAway"}) // a response may have no entity
    <T> T result(Exchanged exchanged, Argument<T> type) {
        // the response the filters see, which a failure of their processing carries
        Response response = filterable(exchanged.response(), exchanged.requestContext());
        try {
            if (response instanceof JaxRsMutableResponse jaxRsMutableResponse) {
                filterResponse(jaxRsMutableResponse, exchanged.requestContext());
            }
        } catch (ResponseProcessingException e) {
            throw e;
        } catch (Exception e) {
            // the response was received: its processing failed (JAX-RS, SyncInvoker)
            throw new ResponseProcessingException(response, e instanceof UncheckedIOException u ? u.getCause() : e);
        }
        if (type.getType().equals(Response.class)) {
            return (T) response;
        }
        if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            throw exception(response);
        }
        try {
            return response instanceof JaxRsResponse jaxRsResponse ? jaxRsResponse.readEntity(type) : response.readEntity(type.getType());
        } catch (ResponseProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseProcessingException(response, e);
        }
    }

    /**
     * The request as the request filters changed it: its method, and its entity written through
     * the entity stream they replaced, the one of the ordinary exchanges and of the event sources.
     */
    @SuppressWarnings("unchecked")
    static MutableHttpRequest<Object> changed(JaxRsConfiguration configuration, MutableHttpRequest<Object> request, JaxRsClientRequestContext requestContext) throws IOException {
        request = withChangedMethod(request, requestContext);
        Object entity = requestContext.getEntity();
        if (entity == null) {
            // the entity stream a filter replaced writes nothing
            requestContext.closeReplacedEntityStream(null);
            return request;
        }
        try {
            // the entity as the filters left it, with the media type they left
            // written to bytes when a filter replaced the entity stream, which writes them
            configuration.entityIO().writeBody(request, requestContext.getEntityArgument(), entity, requestContext.isEntityStreamReplaced(),
                requestContext.properties());
            Object body = request.getBody().orElse(null);
            if (body instanceof byte[] bytes) {
                request.body(requestContext.writeThroughEntityStream(bytes));
            } else if (requestContext.isEntityStreamReplaced()) {
                // no writer wrote the entity to the bytes the replaced stream needs
                throw new ProcessingException("No writer of the entity type " + requestContext.getEntityArgument().getType().getName());
            }
        } catch (IOException | RuntimeException e) {
            // the entity stream a filter replaced is closed when the entity is not written through it
            requestContext.closeReplacedEntityStream(e);
            throw e;
        }
        return request;
    }

    /**
     * The request with the method a request filter set, the one of the ordinary exchanges and of
     * the event sources (JAX-RS, {@code ClientRequestContext#setMethod}).
     *
     * @param request        The request the filters saw
     * @param requestContext The context of the request
     * @return The request, a copy with its headers and attributes when the method changed
     */
    static MutableHttpRequest<Object> withChangedMethod(MutableHttpRequest<Object> request, JaxRsClientRequestContext requestContext) {
        String changedMethod = requestContext.getChangedMethod();
        if (changedMethod != null && !changedMethod.equals(request.getMethodName())) {
            HttpMethod httpMethod = methodOf(changedMethod);
            MutableHttpRequest<Object> copy = httpMethod == HttpMethod.CUSTOM
                ? HttpRequest.create(HttpMethod.CUSTOM, request.getUri().toString(), changedMethod)
                : HttpRequest.create(httpMethod, request.getUri().toString());
            request.getHeaders().forEachValue(copy::header);
            request.getAttributes().forEach(copy::setAttribute);
            return copy;
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
        if (!(response instanceof JaxRsResponse)) {
            // a response of the application: the response filters see it as any other
            response = toMutableResponse(response);
        }
        if (response instanceof JaxRsMutableResponse jaxRsMutableResponse) {
            MutableHttpResponse<?> httpResponse = jaxRsMutableResponse.getResponse();
            Object entity = httpResponse.getBody().orElse(null);
            if (entity != null && !(entity instanceof byte[])) {
                configuration.entityIO().writeAbortedBody(httpResponse, (Argument) Argument.of(entity.getClass()), entity);
            }
        }
        return response;
    }

    /**
     * A response of another implementation of {@link Response}, e.g. a subclass of the
     * application: its status, headers and entity.
     */
    @SuppressWarnings("unchecked")
    static JaxRsMutableResponse toMutableResponse(Response response) {
        Response.StatusType statusInfo = response.getStatusInfo();
        String reason = statusInfo == null ? null : statusInfo.getReasonPhrase();
        MutableHttpResponse<Object> httpResponse = HttpResponseFactory.INSTANCE.status(response.getStatus(), reason);
        MultivaluedMap<String, Object> headers = response.getMetadata();
        if (headers != null) {
            headers.forEach((name, values) -> {
                for (Object value : values) {
                    if (value != null) {
                        httpResponse.getHeaders().add(name, JaxRsHeaderUtil.headerToString(value));
                    }
                }
            });
        }
        Object entity = response.getEntity();
        if (entity != null) {
            httpResponse.body(entity);
        }
        return new JaxRsMutableResponse(httpResponse);
    }

    /**
     * The response the response filters see, with the readers of the client.
     */
    private Response filterable(Response response, JaxRsClientRequestContext requestContext) {
        if (response instanceof JaxRsMutableResponse jaxRsMutableResponse) {
            JaxRsMutableResponse filterable = jaxRsMutableResponse.withEntityReader(configuration.entityIO().createHttpMessageEntityReader(properties(requestContext)));
            MutableHttpResponse<?> httpResponse = filterable.getResponse();
            httpResponse.removeAttribute(RECEIVED_ENTITY_STREAM, InputStream.class).ifPresent(filterable::setEntityStream);
            return filterable;
        }
        if (response instanceof JaxRsResponse jaxRsResponse) {
            MutableHttpResponse<?> mutableResponse = jaxRsResponse.getResponse().toMutableResponse();
            return new JaxRsMutableResponse(mutableResponse, configuration.entityIO().createHttpMessageEntityReader(properties(requestContext)));
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
            JaxRsClientFilters.filterResponse(configuration, requestContext, jaxRsMutableResponse);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The request, without its entity: it is written after the request filters ran.
     */
    private MutableHttpRequest<Object> createRequest(@Nullable String method, @Nullable Entity<?> entity) {
        Objects.requireNonNull(method, "method");
        // a method that is not a standard one, e.g. PURGE, is a custom one
        HttpMethod httpMethod = methodOf(method);
        MutableHttpRequest<Object> mutableHttpRequest = httpMethod ==
            HttpMethod.CUSTOM ? HttpRequest.create(HttpMethod.CUSTOM, uri.toString(), method) : HttpRequest.create(httpMethod, uri.toString());
        if (mutableHttpHeaders != null) {
            mutableHttpHeaders.forEachValue(mutableHttpRequest::header);
        }
        if (entity != null) {
            // the variant of the entity replaces the one of the headers of the builder (JAX-RS, Invocation.Builder)
            if (entity.getMediaType() != null) {
                mutableHttpRequest = mutableHttpRequest.contentType(MediaType.of(entity.getMediaType().toString()));
            }
            // the variant is complete: a language or an encoding it has not is removed
            mutableHttpRequest.getHeaders().remove(HttpHeaders.CONTENT_LANGUAGE);
            mutableHttpRequest.getHeaders().remove(HttpHeaders.CONTENT_ENCODING);
            Locale language = entity.getLanguage();
            if (language != null) {
                mutableHttpRequest.getHeaders().set(HttpHeaders.CONTENT_LANGUAGE, language.toLanguageTag());
            }
            String encoding = entity.getEncoding();
            if (encoding != null) {
                // the encoding of the variant of the entity (RFC 9110, 8.4)
                mutableHttpRequest.getHeaders().set(HttpHeaders.CONTENT_ENCODING, encoding);
            }
        }
        return mutableHttpRequest;
    }

    /**
     * The method of a method name: a custom one unless it is exactly the name of a standard one,
     * the name of a method being case-sensitive (RFC 9110, 9.1).
     */
    private static HttpMethod methodOf(String method) {
        HttpMethod httpMethod = HttpMethod.parse(method);
        return httpMethod.name().equals(method) ? httpMethod : HttpMethod.CUSTOM;
    }

    /**
     * @param throwable A failure, {@code null} if none
     * @return The failure without the {@link CompletionException} or {@link ExecutionException} around it
     */
    static @Nullable Throwable unwrap(@Nullable Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * The response of a request, not filtered yet, with the context of the request.
     *
     * @param response       The response
     * @param requestContext The context of the request
     */
    record Exchanged(Response response, JaxRsClientRequestContext requestContext) {
    }
}
