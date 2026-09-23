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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.web.router.RouteInfo;
import jakarta.ws.rs.Path;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

/**
 * Runs the container filters of JAX-RS in the filter chain of the server, see
 * {@link JaxRsContainerFilters}.
 *
 * @author graemerocher
 * @since 1.0
 */
@ServerFilter("/**")
@Internal
final class JaxRsFilters {

    private final JaxRsContainerFilters filters;

    JaxRsFilters(JaxRsContainerFilters filters) {
        this.filters = filters;
    }

    @ResponseFilter
    @io.micronaut.http.server.annotation.PreMatching
    MutableHttpResponse<?> filterResponsePreMatch(HttpRequest<?> request,
                                                  MutableHttpResponse<?> mutableHttpResponse) throws IOException {
        return filters.filterResponse(null, request, mutableHttpResponse);
    }

    @ResponseFilter
    MutableHttpResponse<?> filterResponse(@Nullable RouteInfo<?> routeInfo,
                                          HttpRequest<?> request,
                                          MutableHttpResponse<?> mutableHttpResponse) throws IOException {
        if (routeInfo != null && !isJaxRs(routeInfo)) {
            // Intercept only JaxRs routes
            return mutableHttpResponse;
        }
        return filters.filterResponse(routeInfo, request, mutableHttpResponse);
    }

    @Nullable
    @io.micronaut.http.server.annotation.PreMatching
    @RequestFilter
    HttpResponse<?> filterPreMatchingRequest(MutableHttpRequest<?> request) throws IOException {
        return filters.filterPreMatchingRequest(request);
    }

    @Nullable
    @RequestFilter
    HttpResponse<?> filterRequest(RouteInfo<?> routeInfo, MutableHttpRequest<?> request) throws IOException {
        if (!isJaxRs(routeInfo)) {
            // Intercept only JaxRs routes
            return null;
        }
        return filters.filterRequest(routeInfo, request);
    }

    /**
     * Whether a route is one of a JAX-RS resource method: of a root resource, with its
     * {@code @Path}, or of a sub-resource, whose class may have none, with its request method
     * designator, e.g. {@code @GET}.
     */
    private static boolean isJaxRs(RouteInfo<?> routeInfo) {
        AnnotationMetadata metadata = routeInfo.getAnnotationMetadata();
        return metadata.hasAnnotation(Path.class) || metadata.hasStereotype(jakarta.ws.rs.HttpMethod.class);
    }
}
