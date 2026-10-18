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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The properties of a request of the server, which its filter and interceptor contexts share
 * (JAX-RS, {@code ContainerRequestContext#getProperty} and {@code InterceptorContext#getProperty}).
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsRequestProperties {

    // the properties are an attribute of the request
    private static final String ATTRIBUTE = JaxRsRequestProperties.class.getName();

    private JaxRsRequestProperties() {
    }

    /**
     * The properties of a request.
     *
     * @param request The request
     * @return The properties, created on first use
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> of(HttpRequest<?> request) {
        // the map itself: an attribute of the requested type is not converted
        Map<String, Object> properties = request.getAttribute(ATTRIBUTE, Map.class).orElse(null);
        if (properties == null) {
            properties = new LinkedHashMap<>();
            request.setAttribute(ATTRIBUTE, properties);
        }
        return properties;
    }

    /**
     * The properties of the current request of the server.
     *
     * @return The properties, empty ones outside a request
     */
    public static Map<String, Object> current() {
        return ServerRequestContext.currentRequest()
            .map(JaxRsRequestProperties::of)
            .orElseGet(LinkedHashMap::new);
    }
}
