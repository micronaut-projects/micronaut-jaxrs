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

import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Runs the request and the response filters of a configuration, of an invocation or of an event
 * source: the injected configuration of a shared filter is the one of the caller (JAX-RS 10.2.8).
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
final class JaxRsClientFilters {

    private JaxRsClientFilters() {
    }

    /**
     * Run the request filters, until one aborts the request.
     *
     * @param configuration  The configuration
     * @param requestContext The context of the request
     * @return The response a filter aborted the request with, {@code null} if none did
     * @throws IOException if a filter fails
     */
    static @Nullable Response filterRequest(JaxRsConfiguration configuration, JaxRsClientRequestContext requestContext) throws IOException {
        try (var ignored = configuration.current()) {
            for (ClientRequestFilter filter : configuration.getRequestFilters()) {
                filter.filter(requestContext);
                Response response = requestContext.getResponse();
                if (response != null) {
                    return response;
                }
            }
        }
        return null;
    }

    /**
     * Run the response filters.
     *
     * @param configuration  The configuration
     * @param requestContext The context of the request, {@code null} if unknown
     * @param response       The response they see and may change
     * @throws IOException if a filter fails
     */
    static void filterResponse(JaxRsConfiguration configuration, @Nullable JaxRsClientRequestContext requestContext,
                               JaxRsMutableResponse response) throws IOException {
        List<ClientResponseFilter> filters = configuration.getResponseFilters();
        if (!filters.isEmpty()) {
            var responseContext = new JaxRsClientResponseContext(response);
            try (var ignored = configuration.current()) {
                for (ClientResponseFilter filter : filters) {
                    filter.filter(requestContext, responseContext);
                }
            }
        }
    }
}
