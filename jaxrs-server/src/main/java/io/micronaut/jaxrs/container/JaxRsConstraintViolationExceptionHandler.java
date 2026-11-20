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
package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Providers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers the violations the JAX-RS runtime found (JAX-RS 7.6): with the exception mapper of the
 * application for the nearest type, e.g. an {@code ExceptionMapper<ValidationException>}, else
 * with 500 for a return value and 400 with the messages of the violations otherwise. It is more
 * specific than the handler of Micronaut Validation, which answers the violations of Micronaut
 * beans.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Singleton
@Produces
@Internal
@Requires(classes = jakarta.validation.ConstraintViolationException.class)
final class JaxRsConstraintViolationExceptionHandler implements ExceptionHandler<JaxRsConstraintViolationException, HttpResponse<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsConstraintViolationExceptionHandler.class);

    private final ErrorResponseProcessor<?> responseProcessor;
    private final Providers providers;

    JaxRsConstraintViolationExceptionHandler(ErrorResponseProcessor<?> responseProcessor, Providers providers) {
        this.responseProcessor = responseProcessor;
        this.providers = providers;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public HttpResponse<?> handle(HttpRequest request, JaxRsConstraintViolationException exception) {
        ExceptionMapper mapper = JaxRsProviders.registeredExceptionMapper(providers, exception.getClass());
        if (mapper != null) {
            return JaxRsGlobalExceptionHandler.map(request, mapper, exception);
        }
        // the completion callbacks of an asynchronous response receive an unmapped exception
        request.setAttribute(JaxRsAsyncResponse.UNMAPPED_EXCEPTION, exception);
        if (exception.isReturnValue()) {
            // a resource that returns an invalid value is the server's error, which is not disclosed
            LOG.error("The return value of a JAX-RS resource method is not valid: {}", exception.getMessage());
            return responseProcessor.processResponse(ErrorContext.builder(request)
                .errorMessage("Internal Server Error")
                .cause(exception)
                .build(), HttpResponse.serverError());
        }
        List<String> messages = new ArrayList<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            messages.add(message(violation));
        }
        return responseProcessor.processResponse(ErrorContext.builder(request)
            .errorMessages(messages)
            .cause(exception)
            .build(), HttpResponse.badRequest());
    }

    /**
     * The message of a violation with the path of the value, without the method, e.g.
     * {@code name: must not be blank}.
     */
    private static String message(ConstraintViolation<?> violation) {
        StringBuilder path = new StringBuilder();
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.METHOD || node.getKind() == ElementKind.CONSTRUCTOR || node.getName() == null) {
                continue;
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            path.append(node.getName());
        }
        return path.isEmpty() ? violation.getMessage() : path + ": " + violation.getMessage();
    }
}
