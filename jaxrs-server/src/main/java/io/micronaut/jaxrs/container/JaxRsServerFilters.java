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
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.builder.HttpRouteBuilder;
import io.micronaut.web.router.builder.HttpRoutes;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

import java.net.URI;
import java.net.URISyntaxException;

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
    private final ApplicationProvider applicationProvider;

    JaxRsServerFilters(JaxRsContainerFilters filters, ApplicationProvider applicationProvider) {
        this.filters = filters;
        this.applicationProvider = applicationProvider;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void routes(HttpRouteBuilder routes) {
        routes.filter("/**").preMatching()
            .beforeReplacing(filters::filterPreMatchingRequest)
            .after((request, response) -> {
                RouteInfo<?> route = RouteAttributes.getRouteInfo(request).orElse(null);
                if (route != null && !isJaxRs(route)) {
                    // a route that is not one of a resource method, e.g. of a controller
                    return;
                }
                resolveLocation(request, response);
                MutableHttpResponse<?> filtered = filters.filterResponse(route, request, response);
                if (filtered != response) {
                    // the response of a JAX-RS Response entity: the filters run on it
                    replace(response, filtered);
                }
                request.getAttribute(JaxRsRouteSupport.SSE_EVENT_SINK, JaxRsSseEventSink.class).ifPresent(sink -> {
                    if (response.getStatus().getCode() >= 300) {
                        // the resource method failed, e.g. with a 503: the error is the response
                        sink.close();
                        return;
                    }
                    // the events a resource method sends to its sink (JAX-RS 9.3)
                    ((MutableHttpResponse) response).status(HttpStatus.OK).body(sink.publisher());
                    response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM);
                });
            });
    }

    /**
     * A relative location of a response, e.g. of {@code Response.created(URI.create("created"))},
     * is resolved against the base URI of the application (JAX-RS, {@code ResponseBuilder#location}).
     */
    private void resolveLocation(HttpRequest<?> request, MutableHttpResponse<?> response) {
        String location = response.getHeaders().get(HttpHeaders.LOCATION);
        if (location == null) {
            return;
        }
        URI uri;
        try {
            uri = new URI(location);
        } catch (URISyntaxException e) {
            return;
        }
        if (!uri.isAbsolute()) {
            URI base = new UriInfoImpl(request, applicationProvider.getPath(), applicationProvider.getContextPath()).getBaseUri();
            response.getHeaders().set(HttpHeaders.LOCATION, base.resolve(uri).toString());
        }
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
