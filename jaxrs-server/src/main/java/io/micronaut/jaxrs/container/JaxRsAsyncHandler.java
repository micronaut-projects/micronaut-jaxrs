/*
 * Copyright 2017-2026 original authors
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
import io.micronaut.web.router.builder.PathVariables;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletionStage;

/**
 * The generated method of an asynchronous resource method, called with what the request body was
 * read into: the bytes of the entity, or the form. Its parameters are named apart from the ones of
 * the route handler whose generated lambda contains it.
 *
 * @param <T> The type the body was read into
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
@FunctionalInterface
public interface JaxRsAsyncHandler<T> {

    /**
     * @param readRequest       The request
     * @param readPathVariables The path variables
     * @param readBody          The body, {@code null} for an empty entity
     * @return The response
     * @throws Exception If the resource method fails
     */
    CompletionStage<? extends HttpResponse<?>> handle(HttpRequest<?> readRequest, PathVariables readPathVariables, @Nullable T readBody) throws Exception;
}
