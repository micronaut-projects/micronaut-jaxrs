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
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;

import java.util.Optional;

/**
 * Request method resolved from the mutable Jakarta REST request context.
 *
 * @param method The Micronaut HTTP method.
 * @param name The original method name.
 */
@Internal
record JaxRsRequestMethod(HttpMethod method, String name) {

    /**
     * Resolves the effective request method after Jakarta REST request filters
     * have had the opportunity to override the method name.
     *
     * @param request The current request.
     * @return The effective request method.
     */
    static JaxRsRequestMethod forRequest(HttpRequest<?> request) {
        Optional<String> override = request.getAttribute(JaxRsContainerRequestContext.REQUEST_METHOD_ATTRIBUTE, String.class);
        if (override.isPresent()) {
            String methodName = override.get();
            return new JaxRsRequestMethod(HttpMethod.parse(methodName), methodName);
        }
        return new JaxRsRequestMethod(request.getMethod(), request.getMethodName());
    }

    /**
     * @return Whether the effective request method is {@code HEAD}.
     */
    boolean isHead() {
        return method == HttpMethod.HEAD;
    }

    /**
     * Matches an annotation-derived HTTP method name, including Jakarta REST's
     * implicit {@code HEAD} support for {@code GET} resources.
     *
     * @param targetHttpMethod The route HTTP method name.
     * @return Whether this request can invoke the route.
     */
    boolean matches(String targetHttpMethod) {
        return targetHttpMethod.equals(name) || isHead() && targetHttpMethod.equals(HttpMethod.GET.name());
    }
}
