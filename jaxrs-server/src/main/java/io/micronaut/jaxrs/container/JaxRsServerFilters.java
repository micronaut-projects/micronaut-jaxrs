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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.builder.HttpRouteBuilder;
import io.micronaut.web.router.builder.HttpRoutes;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

/**
 * The container filters of JAX-RS that are not the ones of a resource method: the pre-matching
 * request filters, which run before the request is matched, and the response filters of every
 * response of the application, also of a request no route matched. The request filters of a
 * resource method are filters of its route, see {@link JaxRsRouteSupport#configure}.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Singleton
@Internal
final class JaxRsServerFilters implements HttpRoutes {

    private final JaxRsContainerFilters filters;

    JaxRsServerFilters(JaxRsContainerFilters filters) {
        this.filters = filters;
    }

    @Override
    public void routes(HttpRouteBuilder routes) {
        routes.filter("/**").preMatching()
            .before(filters::filterPreMatchingRequest)
            .after((request, response) -> {
                RouteInfo<?> route = RouteAttributes.getRouteInfo(request).orElse(null);
                if (route != null && !isJaxRs(route)) {
                    // a route that is not one of a resource method, e.g. of a controller
                    return;
                }
                MutableHttpResponse<?> filtered = filters.filterResponse(route, request, response);
                if (filtered != response) {
                    // the response of a JAX-RS Response entity: the filters run on it
                    replace(response, filtered);
                }
            });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void replace(MutableHttpResponse<?> response, MutableHttpResponse<?> replacement) {
        response.status(replacement.getStatus(), replacement.reason());
        for (String name : response.getHeaders().names()) {
            response.getHeaders().remove(name);
        }
        replacement.getHeaders().forEach((name, values) -> values.forEach(value -> response.getHeaders().add(name, value)));
        replacement.getAttributes().forEach(response::setAttribute);
        ((MutableHttpResponse) response).body(replacement.getBody().orElse(null));
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
