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
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Matrix parameters, {@code /cars;color=red/details}: the JAX-RS route template engine matches the
 * path without them, and they are read from the path of the request for {@code @MatrixParam} and
 * {@link PathSegment}.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsPathSegments {

    private JaxRsPathSegments() {
    }

    /**
     * The values of a matrix parameter of the last segment of the request path.
     *
     * @param request The request
     * @param name    The name of the parameter
     * @param encoded Whether the values are not decoded
     * @return The decoded values, empty if the parameter is not present
     */
    static List<String> values(HttpRequest<?> request, String name, boolean encoded) {
        String path = request.getUri().getRawPath();
        if (path == null || path.indexOf(';') < 0) {
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
                String value = equals < 0 ? "" : part.substring(equals + 1);
                values.add(encoded ? value : URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    /**
     * The path segment a path variable matched, with its matrix parameters.
     *
     * @param request The request
     * @param value   The value of the variable
     * @param encoded Whether the path and the parameters are not decoded
     * @return The segment
     */
    static PathSegment pathSegment(HttpRequest<?> request, String value, boolean encoded) {
        String path = request.getUri().getRawPath();
        for (String segment : path.split("/")) {
            String[] parts = segment.split(";");
            String decoded = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (decoded.equals(value) || parts[0].equals(value)) {
                MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
                for (int i = 1; i < parts.length; i++) {
                    int equals = parts[i].indexOf('=');
                    String key = equals < 0 ? parts[i] : parts[i].substring(0, equals);
                    String parameter = equals < 0 ? "" : parts[i].substring(equals + 1);
                    parameters.add(encoded ? key : URLDecoder.decode(key, StandardCharsets.UTF_8),
                        encoded ? parameter : URLDecoder.decode(parameter, StandardCharsets.UTF_8));
                }
                return new Segment(encoded ? parts[0] : decoded, parameters);
            }
        }
        return new Segment(value, new MultivaluedHashMap<>());
    }

    /**
     * A path segment and its matrix parameters.
     *
     * @param getPath             The path of the segment
     * @param getMatrixParameters Its matrix parameters
     */
    private record Segment(String getPath, MultivaluedMap<String, String> getMatrixParameters) implements PathSegment {
    }
}
