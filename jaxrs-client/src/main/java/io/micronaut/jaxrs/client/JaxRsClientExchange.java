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
import io.micronaut.http.client.exceptions.HttpClientResponseException;
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
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The exchange of an invocation: the request filters, the writing of the request entity, the
 * transport, then the response filters and the reading of the response entity (JAX-RS 6.4), apart
 * from the API and the threading of the invocation.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
final class JaxRsClientExchange {

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
        try {
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
            // the injected configuration of a shared filter is the one of this invocation (JAX-RS 10.2.8)
            Response aborted = JaxRsClientFilters.filterRequest(configuration, requestContext);
            if (aborted != null) {
                future.complete(new Exchanged(written(aborted), requestContext));
                return future;
            }
            request = changed(request, requestContext);
            // the relative links of the response are resolved against the URI of the request
            URI requestUri = request.getUri();
            CompletableFuture<HttpResponse<ByteBuffer<?>>> exchange = client.getHttpClient().toAsync().exchange(request).toCompletableFuture();
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
                        .attribute(JaxRsResponse.REQUEST_URI, requestUri)), requestContext));
                } else if (throwable != null) {
                    future.completeExceptionally(new ProcessingException(throwable));
                } else if (response == null) {
                    future.completeExceptionally(new ProcessingException("Expected a response"));
                } else {
                    future.complete(new Exchanged(new JaxRsMutableResponse(response.toMutableResponse()
                        .attribute(JaxRsResponse.REQUEST_URI, requestUri)), requestContext));
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
     * The result of an invocation: the response, filtered by the response filters, or its entity,
     * or the exception of its status when it is not successful (JAX-RS, {@code SyncInvoker}).
     */
    @SuppressWarnings({"unchecked", "NullAway"}) // a response may have no entity
    <T> T result(Exchanged exchanged, Argument<T> type) {
        Response response;
        try {
            response = filterResponse(exchanged.response(), exchanged.requestContext());
        } catch (ResponseProcessingException e) {
            throw e;
        } catch (Exception e) {
            // the response was received: its processing failed (JAX-RS, SyncInvoker)
            throw new ResponseProcessingException(exchanged.response(), e instanceof UncheckedIOException u ? u.getCause() : e);
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
            // written to bytes when a filter replaced the entity stream, which writes them
            configuration.entityIO().writeBody(request, requestContext.getEntityArgument(), entity, requestContext.isEntityStreamReplaced(),
                requestContext.properties());
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
        if (!(response instanceof JaxRsResponse)) {
            // a response of the application: the response filters see it as any other
            response = toMutableResponse(response);
        }
        if (response instanceof JaxRsMutableResponse jaxRsMutableResponse) {
            MutableHttpResponse<?> httpResponse = jaxRsMutableResponse.getResponse();
            Object entity = httpResponse.getBody().orElse(null);
            if (entity != null && !(entity instanceof byte[])) {
                configuration.entityIO().writeBody(httpResponse, (Argument) Argument.of(entity.getClass()), entity);
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

    private Response filterResponse(Response response, JaxRsClientRequestContext requestContext) {
        if (response instanceof JaxRsMutableResponse jaxRsMutableResponse) {
            jaxRsMutableResponse = jaxRsMutableResponse.withEntityReader(configuration.entityIO().createHttpMessageEntityReader(properties(requestContext)));
            filterResponse(jaxRsMutableResponse, requestContext);
            return jaxRsMutableResponse;
        }
        if (response instanceof JaxRsResponse jaxRsResponse) {
            MutableHttpResponse<?> mutableResponse = jaxRsResponse.getResponse().toMutableResponse();
            JaxRsMutableResponse jaxRsMutableResponse = new JaxRsMutableResponse(mutableResponse, configuration.entityIO().createHttpMessageEntityReader(properties(requestContext)));
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
