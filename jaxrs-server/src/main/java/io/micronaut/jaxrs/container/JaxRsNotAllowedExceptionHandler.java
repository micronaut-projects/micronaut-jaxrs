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
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.NotAllowedException;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Providers;

import java.util.ArrayList;

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
final class JaxRsNotAllowedExceptionHandler implements ExceptionHandler<NotAllowedException, HttpResponse<?>> {
    private final Providers providers;

    /**
     * Constructor.
     *
     * @param providers The providers
     */
    @Inject
    JaxRsNotAllowedExceptionHandler(Providers providers) {
        this.providers = providers;
    }

    @Override
    public HttpResponse<?> handle(HttpRequest request, NotAllowedException exception) {
        var allowedMethods = new ArrayList<>(exception.getAllowedMethods());
        var notAllowedException = new jakarta.ws.rs.NotAllowedException(
            allowedMethods.get(0),
            allowedMethods.subList(1, allowedMethods.size()).toArray(String[]::new)
        );
        ExceptionMapper exceptionMapper = providers.getExceptionMapper(notAllowedException.getClass());
        Response response;
        if (exceptionMapper != null) {
            response = exceptionMapper.toResponse(notAllowedException);
        } else {
            response = notAllowedException.getResponse();
        }
        return ((JaxRsMutableResponse) response).getResponse();
    }

}
