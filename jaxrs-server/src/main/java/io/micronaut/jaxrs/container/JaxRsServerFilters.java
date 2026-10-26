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
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.jaxrs.runtime.ext.bind.UriInfoImpl;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.builder.HttpRouteBuilder;
import io.micronaut.web.router.builder.HttpRoutes;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;

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
    public void routes(HttpRouteBuilder routes) {
        routes.serverFilter("/**").preMatching()
            .beforeReplacingAsync(request -> {
                normalize(request);
                return filters.filterPreMatchingRequestAsync(request);
            }).and()
            .after((request, response) -> {
                try {
                    filterResponse(request, response);
                } finally {
                    // the response is processed: the completion callbacks of an asynchronous response, if
                    // the server does not call them once the response was written
                    JaxRsAsyncResponse.completed(request);
                }
            });
    }

    /**
     * Normalize the request URI before it is matched, the one {@code UriInfo} reflects (JAX-RS
     * 3.7.1): the syntax-based normalization of RFC 3986, 6.2.2.
     */
    private static void normalize(MutableHttpRequest<?> request) {
        URI uri = request.getUri();
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.indexOf('%') < 0 && rawPath.indexOf('.') < 0) {
            return;
        }
        String normalized = normalizePath(rawPath);
        if (normalized.equals(rawPath)) {
            return;
        }
        StringBuilder target = new StringBuilder();
        if (uri.getRawAuthority() != null) {
            target.append(uri.getScheme()).append("://").append(uri.getRawAuthority());
        }
        target.append(normalized);
        if (uri.getRawQuery() != null) {
            target.append('?').append(uri.getRawQuery());
        }
        try {
            request.uri(new URI(target.toString()));
        } catch (URISyntaxException e) {
            // left as it is
        }
    }

    /**
     * A raw path normalized (RFC 3986, 6.2.2): the percent-encoded unreserved characters decoded,
     * the hexadecimal digits of the other percent-encodings in upper case, then the dot segments
     * removed (RFC 3986, 5.2.4).
     *
     * @param rawPath The raw path
     * @return The normalized path
     */
    static String normalizePath(String rawPath) {
        StringBuilder decoded = new StringBuilder(rawPath.length());
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c == '%' && i + 2 < rawPath.length()) {
                int high = Character.digit(rawPath.charAt(i + 1), 16);
                int low = Character.digit(rawPath.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    char value = (char) (high << 4 | low);
                    if (isUnreserved(value)) {
                        decoded.append(value);
                    } else {
                        decoded.append('%').append(Character.toUpperCase(rawPath.charAt(i + 1)))
                            .append(Character.toUpperCase(rawPath.charAt(i + 2)));
                    }
                    i += 2;
                    continue;
                }
            }
            decoded.append(c);
        }
        return removeDotSegments(decoded.toString());
    }

    private static boolean isUnreserved(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /**
     * RFC 3986, 5.2.4: the segments {@code .} and {@code ..} of an absolute path.
     */
    private static String removeDotSegments(String path) {
        if (!path.startsWith("/") || path.indexOf('.') < 0) {
            return path;
        }
        String[] segments = path.substring(1).split("/", -1);
        ArrayDeque<String> output = new ArrayDeque<>();
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean last = i == segments.length - 1;
            if (segment.equals(".") || segment.equals("..")) {
                if (segment.equals("..") && !output.isEmpty()) {
                    output.removeLast();
                }
                if (last) {
                    // a path that ends with a dot segment ends with a slash
                    output.addLast("");
                }
            } else {
                output.addLast(segment);
            }
        }
        return "/" + String.join("/", output);
    }

    /**
     * Filter the response of a resource method with the JAX-RS response filters.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void filterResponse(HttpRequest<?> request, MutableHttpResponse<?> response) throws IOException {
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
        if (JaxRsRouteSupport.resourceMethodKey(routeInfo) != null) {
            // a route of the JAX-RS resource methods, of a bean or not
            return true;
        }
        AnnotationMetadata metadata = routeInfo.getAnnotationMetadata();
        return metadata.hasAnnotation(Path.class) || metadata.hasStereotype(jakarta.ws.rs.HttpMethod.class);
    }
}
