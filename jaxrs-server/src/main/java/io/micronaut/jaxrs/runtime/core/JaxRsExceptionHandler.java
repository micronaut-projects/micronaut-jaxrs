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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import jakarta.inject.Singleton;
import javax.ws.rs.WebApplicationException;

/**
 * Handles JAX-RS exceptions that occur during the execution of an HTTP request.
 *
 * @author guillermocalvo
 */
@Singleton
@Produces
public class JaxRsExceptionHandler implements ExceptionHandler<WebApplicationException, HttpResponse<?>> {
    private final ErrorResponseProcessor<?> responseProcessor;

    /**
     * Constructor.
     * @param responseProcessor Error Response Processor
     */
    public JaxRsExceptionHandler(ErrorResponseProcessor<?> responseProcessor) {
        this.responseProcessor = responseProcessor;
    }

    @Override
    public HttpResponse<?> handle(HttpRequest request, WebApplicationException exception) {
        MutableHttpResponse<?> response = HttpResponse.status(HttpStatus.valueOf(exception.getResponse().getStatus()));
        return responseProcessor.processResponse(ErrorContext.builder(request)
                .errorMessage(exception.getMessage())
                .cause(exception)
                .build(), response);
    }
}
