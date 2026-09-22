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
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.server.annotation.PreMatching;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Matrix parameters, {@code /cars;color=red/details}, are not part of the path a route matches:
 * before routing, they are removed from the request path, and the original path is kept for
 * {@code @MatrixParam}. Requests without matrix parameters are not changed.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
final class JaxRsMatrixParams implements Ordered {

    /**
     * The request attribute with the path of a request that had matrix parameters.
     */
    static final String MATRIX_PATH = JaxRsMatrixParams.class.getName() + ".path";

    @PreMatching
    @RequestFilter
    @Nullable HttpRequest<?> removeMatrixParams(HttpRequest<?> request) {
        URI uri = request.getUri();
        String path = uri.getRawPath();
        if (path == null || path.indexOf(';') < 0) {
            return null;
        }
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            int semicolon = segments[i].indexOf(';');
            if (semicolon >= 0) {
                segments[i] = segments[i].substring(0, semicolon);
            }
        }
        String stripped = String.join("/", segments);
        request.setAttribute(MATRIX_PATH, path);
        String query = uri.getRawQuery();
        // the request the route is matched with
        return request.mutate().uri(URI.create(stripped + (query == null ? "" : "?" + query)));
    }

    /**
     * The values of a matrix parameter of the last segment of the request path.
     *
     * @param request The request
     * @param name    The name of the parameter
     * @return The decoded values, empty if the parameter is not present
     */
    static List<String> values(HttpRequest<?> request, String name) {
        @Nullable String path = request.getAttribute(MATRIX_PATH, String.class).orElse(null);
        if (path == null) {
            return List.of();
        }
        int lastSlash = path.lastIndexOf('/');
        String segment = path.substring(lastSlash + 1);
        List<String> values = new ArrayList<>(1);
        String[] parts = segment.split(";");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int equals = part.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                values.add(equals < 0 ? "" : URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    @Override
    public int getOrder() {
        // before the pre-matching container request filters, which see the path without them
        return HIGHEST_PRECEDENCE;
    }
}
