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
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.UriRouteMatch;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import org.jspecify.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
            String key = decode(equals < 0 ? part : part.substring(0, equals));
            if (key.equals(name)) {
                String value = equals < 0 ? "" : part.substring(equals + 1);
                values.add(encoded ? value : decode(value));
            }
        }
        return values;
    }

    /**
     * The path segments a path variable matched, with their matrix parameters: located by the
     * position of the variable in the template of the matched route, else by their text.
     *
     * @param request The request
     * @param name    The name of the variable
     * @param value   The value of the variable, its segments separated by slashes
     * @param encoded Whether the paths and the parameters are not decoded
     * @return The segments
     */
    static List<PathSegment> pathSegments(HttpRequest<?> request, String name, String value, boolean encoded) {
        String[] segments = request.getUri().getRawPath().split("/", -1);
        Location location = locate(request, name, segments.length);
        if (location != null) {
            // split as it appears in the path: an encoded slash is not a separator
            List<PathSegment> located = new ArrayList<>(location.count());
            for (int i = 0; i < location.count(); i++) {
                located.add(segment(segments[location.index() + i], encoded));
            }
            return located;
        }
        String[] values = value.split("/", -1);
        List<PathSegment> located = new ArrayList<>(values.length);
        for (String v : values) {
            located.add(pathSegment(segments, v, encoded));
        }
        return located;
    }

    /**
     * The path segment a path variable matched, with its matrix parameters: the whole segment
     * when the variable is a part of it, the last one when it matched several (JAX-RS
     * {@code PathParam}), see {@link #pathSegments(HttpRequest, String, String, boolean)}.
     *
     * @param request The request
     * @param name    The name of the variable
     * @param value   The value of the variable
     * @param encoded Whether the path and the parameters are not decoded
     * @return The segment
     */
    static PathSegment pathSegment(HttpRequest<?> request, String name, String value, boolean encoded) {
        String[] segments = request.getUri().getRawPath().split("/", -1);
        Location location = locate(request, name, segments.length);
        if (location != null) {
            return segment(segments[location.index() + location.count() - 1], encoded);
        }
        return pathSegment(segments, value, encoded);
    }

    /**
     * The first segment with the text of a value, for a variable whose position is not known.
     */
    private static PathSegment pathSegment(String[] segments, String value, boolean encoded) {
        for (String segment : segments) {
            if (matches(segment, value)) {
                return segment(segment, encoded);
            }
        }
        return new Segment(value, new MultivaluedHashMap<>());
    }

    /**
     * The segments of the request path the last occurrence of a variable in the template of the
     * matched route is in: counted by the slashes of the template before it, and in the raw values
     * of the variables before it, from the end of the path, which can have a prefix the template
     * does not, e.g. the context path.
     *
     * @param request  The request
     * @param name     The name of the variable
     * @param segments The number of segments of the request path
     * @return The location, {@code null} if not known
     */
    private static @Nullable Location locate(HttpRequest<?> request, String name, int segments) {
        UriRouteMatch<?, ?> match = RouteAttributes.getRouteMatch(request)
            .filter(UriRouteMatch.class::isInstance)
            .<UriRouteMatch<?, ?>>map(m -> (UriRouteMatch<?, ?>) m)
            .orElse(null);
        if (match == null) {
            return null;
        }
        Map<String, Object> rawValues = match.getRawVariableValues();
        String template = JaxRsRouteTemplateEngine.withoutMarks(match.getRouteInfo().getRouteTemplate().expression());
        int slashes = 0;
        int index = -1;
        int count = 1;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '/') {
                slashes++;
            } else if (c == '{') {
                // the end of the variable, its regular expression can have braces
                int depth = 1;
                int end = i + 1;
                while (end < template.length() && depth > 0) {
                    char e = template.charAt(end);
                    if (e == '{') {
                        depth++;
                    } else if (e == '}') {
                        depth--;
                    }
                    end++;
                }
                String variable = template.substring(i + 1, Math.max(i + 1, end - 1));
                int colon = variable.indexOf(':');
                String variableName = (colon < 0 ? variable : variable.substring(0, colon)).trim();
                Object raw = rawValues.get(variableName);
                // a variable over several segments
                int over = raw == null ? 0 : (int) raw.toString().chars().filter(ch -> ch == '/').count();
                if (variableName.equals(name)) {
                    index = slashes;
                    count = over + 1;
                }
                slashes += over;
                i = end;
                continue;
            }
            i++;
        }
        String path = request.getUri().getRawPath();
        // a trailing slash the template does not have
        int pathSlashes = segments - 1 - (path.endsWith("/") && !template.endsWith("/") ? 1 : 0);
        int offset = pathSlashes - slashes;
        if (index < 0 || offset < 0 || index + offset + count > segments) {
            return null;
        }
        return new Location(index + offset, count);
    }

    private static boolean matches(String segment, String value) {
        int semicolon = segment.indexOf(';');
        String path = semicolon < 0 ? segment : segment.substring(0, semicolon);
        return path.equals(value) || decode(path).equals(value);
    }

    private static PathSegment segment(String segment, boolean encoded) {
        String[] parts = segment.split(";");
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            String key = equals < 0 ? parts[i] : parts[i].substring(0, equals);
            String parameter = equals < 0 ? "" : parts[i].substring(equals + 1);
            parameters.add(encoded ? key : decode(key), encoded ? parameter : decode(parameter));
        }
        String path = parts.length == 0 ? "" : parts[0];
        return new Segment(encoded ? path : decode(path), parameters);
    }

    /**
     * Percent-decode a part of a path: a {@code +} of a path is not a space.
     */
    private static String decode(String value) {
        return value.indexOf('+') < 0 ? URLDecoder.decode(value, StandardCharsets.UTF_8)
            : URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * A path segment and its matrix parameters.
     *
     * @param getPath             The path of the segment
     * @param getMatrixParameters Its matrix parameters
     */
    private record Segment(String getPath, MultivaluedMap<String, String> getMatrixParameters) implements PathSegment {
    }

    /**
     * The path segments a variable matched.
     *
     * @param index The index of its first segment in the request path
     * @param count The number of its segments
     */
    private record Location(int index, int count) {
    }
}
