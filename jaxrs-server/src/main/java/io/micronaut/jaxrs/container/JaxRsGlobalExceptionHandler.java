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
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Providers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final String USED_EXCEPTION_MAPPER = "INTERNAL_MICRONAUT_JAXRS_USED_EXCEPTION_MAPPER";
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
        ExceptionMapper exceptionMapper = providers.getExceptionMapper(exception.getClass());
        if (exceptionMapper != null) {
            String exceptionMapperName = exceptionMapper.getClass().getName();
            Object previousMapper = request.getAttributes().get(USED_EXCEPTION_MAPPER, String.class, null);
            if (exceptionMapperName.equals(previousMapper)) {
                return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            request.getAttributes().put(USED_EXCEPTION_MAPPER, exceptionMapperName);
            return ((JaxRsMutableResponse) exceptionMapper.toResponse(exception)).getResponse();
        }
        return responseProcessor.processResponse(ErrorContext.builder(request)
            .errorMessage(exception.getMessage())
            .cause(exception)
            .build(), HttpResponse.badRequest());
    }
}
