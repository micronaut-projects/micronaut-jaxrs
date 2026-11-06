/*
 * Copyright 2017-2020 original authors
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
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import io.micronaut.http.codec.CodecException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Providers;
import io.micronaut.jaxrs.common.JaxRsIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Handles JAX-RS exceptions that occur during the execution of an HTTP request.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Singleton
@Produces
@Internal
final class JaxRsGlobalExceptionHandler implements ExceptionHandler<Throwable, HttpResponse<?>> {
    private static final Logger LOG = LoggerFactory.getLogger(JaxRsGlobalExceptionHandler.class);
    private static final String MAPPED = JaxRsGlobalExceptionHandler.class.getName() + ".mapped";
    private static final String ENTITY_FAILURE = JaxRsGlobalExceptionHandler.class.getName() + ".entityFailure";
    private final ErrorResponseProcessor<?> responseProcessor;
    private final Providers providers;

    /**
     * Constructor.
     *
     * @param responseProcessor Error Response Processor
     * @param providers         The providers
     */
    @Inject
    JaxRsGlobalExceptionHandler(ErrorResponseProcessor<?> responseProcessor, Providers providers) {
        this.responseProcessor = responseProcessor;
        this.providers = providers;
    }

    @Override
    public HttpResponse<?> handle(HttpRequest request, Throwable exception) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(exception.getMessage(), exception);
        }
        // an unmapped exception of reading the request body is the client's, any other one, e.g. an
        // unchecked exception of a resource method, is a server error (JAX-RS 3.3.4); the parameter
        // conversions throw a WebApplicationException with their status (JAX-RS 3.2)
        // the phase decides, not the type: e.g. a CodecException of a resource method, or of a
        // writer that fails to serialize the response, is a server error
        boolean clientError = exception instanceof CodecException
            && request.getAttribute(ENTITY_FAILURE).orElse(null) == exception;
        if (exception instanceof JaxRsIOException && exception.getCause() != null) {
            // the IOException a provider threw, wrapped to cross Micronaut's reader and writer API
            exception = exception.getCause();
        }
        ExceptionMapper exceptionMapper = JaxRsProviders.registeredExceptionMapper(providers, exception.getClass());
        if (exceptionMapper != null) {
            return map(request, exceptionMapper, exception);
        }
        // the completion callbacks of an asynchronous response receive an unmapped exception
        request.setAttribute(JaxRsAsyncResponse.UNMAPPED_EXCEPTION, exception);
        String message;
        if (clientError) {
            message = Objects.requireNonNullElse(exception.getMessage(), "Bad Request");
        } else {
            // the message of a server error, e.g. a connection string, is logged, not disclosed
            LOG.error("Unexpected error occurred: {}", exception.getMessage(), exception);
            message = "Internal Server Error";
        }
        return responseProcessor.processResponse(ErrorContext.builder(request)
            .errorMessage(message)
            .cause(exception)
            .build(), clientError ? HttpResponse.badRequest() : HttpResponse.serverError());
    }

    /**
     * Mark the exception of reading the entity of a request: unmapped, it is the client's.
     *
     * @param request   The request
     * @param exception The exception
     */
    static void entityFailed(HttpRequest<?> request, CodecException exception) {
        request.setAttribute(ENTITY_FAILURE, exception);
    }

    /**
     * @param request The request
     * @return Whether an exception of the request was mapped already, see {@link #map}
     */
    static boolean isMapped(HttpRequest<?> request) {
        return request.getAttribute(MAPPED).isPresent();
    }

    /**
     * Use the response of an exception, e.g. of a {@link jakarta.ws.rs.WebApplicationException},
     * once per request like a mapped one, see {@link #map}.
     *
     * @param request  The request
     * @param response The response of the exception
     * @return The response
     */
    static HttpResponse<?> mapped(HttpRequest<?> request, jakarta.ws.rs.core.Response response) {
        if (request.getAttribute(MAPPED).isPresent()) {
            return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        request.setAttribute(MAPPED, true);
        return JaxRsRouteSupport.toHttpResponse(response);
    }

    /**
     * Map an exception with an exception mapper, once per request: an exception thrown while the
     * response mapped from an exception is processed is not mapped again, whichever mapper would
     * map it, the response is a server error (JAX-RS 4.4).
     *
     * @param request   The request
     * @param mapper    The exception mapper
     * @param exception The exception
     * @return The response
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static HttpResponse<?> map(HttpRequest<?> request, ExceptionMapper mapper, Throwable exception) {
        if (request.getAttribute(MAPPED).isPresent()) {
            return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        request.setAttribute(MAPPED, true);
        return JaxRsRouteSupport.toHttpResponse(mapper.toResponse(exception));
    }
}
