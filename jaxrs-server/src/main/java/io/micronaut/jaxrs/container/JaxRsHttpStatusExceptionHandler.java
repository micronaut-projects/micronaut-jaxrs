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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.NotAcceptableException;
import io.micronaut.http.server.exceptions.NotFoundException;
import io.micronaut.http.server.exceptions.UnsupportedMediaException;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Providers;

/**
 * Handles JAX-RS exceptions that occur during the execution of an HTTP request.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Singleton
@Produces
@Internal
@Order(Ordered.HIGHEST_PRECEDENCE)
final class JaxRsHttpStatusExceptionHandler implements ExceptionHandler<HttpStatusException, HttpResponse<?>> {
    private final ErrorResponseProcessor<?> responseProcessor;
    private final Providers providers;

    /**
     * Constructor.
     *
     * @param responseProcessor Error Response Processor
     * @param providers         The providers
     */
    @Inject
    JaxRsHttpStatusExceptionHandler(ErrorResponseProcessor<?> responseProcessor, Providers providers) {
        this.responseProcessor = responseProcessor;
        this.providers = providers;
    }

    @Override
    public HttpResponse<?> handle(HttpRequest request, HttpStatusException exception) {
        WebApplicationException webApplicationException = remap(exception);
        if (webApplicationException != null) {
            ExceptionMapper exceptionMapper = providers.getExceptionMapper(webApplicationException.getClass());
            Response response;
            if (exceptionMapper != null) {
                response = exceptionMapper.toResponse(webApplicationException);
            } else {
                response = webApplicationException.getResponse();
            }
            return ((JaxRsMutableResponse) response).getResponse();
        }
        return responseProcessor.processResponse(ErrorContext.builder(request)
            .errorMessage(exception.getMessage())
            .cause(exception)
            .build(), HttpResponse.badRequest());
    }

    @Nullable
    private WebApplicationException remap(HttpStatusException exception) {
        if (exception instanceof NotAcceptableException notAcceptableException) {
            return new jakarta.ws.rs.NotAcceptableException(notAcceptableException.getMessage(), exception);
        }
        if (exception instanceof NotFoundException notFoundException) {
            return new jakarta.ws.rs.NotFoundException(notFoundException.getMessage(), notFoundException);
        }
        if (exception instanceof UnsupportedMediaException notSupportedException) {
            return new jakarta.ws.rs.NotSupportedException(notSupportedException.getMessage(), notSupportedException);
        }
        return null;
    }
}
