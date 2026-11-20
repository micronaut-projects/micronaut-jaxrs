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
import io.micronaut.http.PathVariables;
import io.micronaut.http.uri.RouteCaptures;
import io.micronaut.http.uri.RouteTemplateVariable;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The resources a request was matched to, from the root resource to the sub-resources of its
 * locators, with the number of path segments each one matched.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsMatched {

    private static final String ATTRIBUTE = JaxRsMatched.class.getName();

    private final List<Object> resources = new ArrayList<>(2);
    private final List<Integer> segments = new ArrayList<>(2);
    private final List<Locator> locators = new ArrayList<>(1);
    private volatile @Nullable Integer locating;

    private JaxRsMatched() {
    }

    /**
     * Record a matched resource.
     *
     * @param request  The request
     * @param resource The resource
     * @param segments The number of path segments matched up to the resource, negative if unknown
     */
    static void add(HttpRequest<?> request, Object resource, int segments) {
        JaxRsMatched matched = get(request);
        if (matched == null) {
            matched = new JaxRsMatched();
            request.setAttribute(ATTRIBUTE, matched);
        }
        matched.resources.add(resource);
        matched.segments.add(segments);
    }

    /**
     * Record the path variables a sub-resource locator matched, with the captures of its locator
     * route, which tell the path segments each variable captured: the router gives the variables
     * of a located route only the last value of a variable the locator and the route both capture.
     *
     * @param request       The request
     * @param pathVariables The path variables of the locator
     * @param captures      The captures of the locator route, see
     *                      {@link io.micronaut.web.router.builder.LocatedRoutes#locatorRouteCapture}, {@code null} if not known
     * @param end           The number of path segments matched up to the end of the locator, negative if unknown
     */
    static void addLocator(HttpRequest<?> request, PathVariables pathVariables, @Nullable RouteCaptures captures, int end) {
        JaxRsMatched matched = get(request);
        if (matched == null) {
            matched = new JaxRsMatched();
            request.setAttribute(ATTRIBUTE, matched);
        }
        matched.locators.add(new Locator(pathVariables, captures, end));
    }

    /**
     * Record that a sub-resource locator is called, or that it returned.
     *
     * @param request  The request
     * @param segments The number of path segments the locator matched, or {@code null} when it returned
     */
    static void locating(HttpRequest<?> request, @Nullable Integer segments) {
        JaxRsMatched matched = get(request);
        if (matched == null) {
            matched = new JaxRsMatched();
            request.setAttribute(ATTRIBUTE, matched);
        }
        matched.locating = segments;
    }

    /**
     * @return The number of path segments matched by the sub-resource locator being called, {@code null}
     * when none is
     */
    public @Nullable Integer locating() {
        return locating;
    }

    /**
     * @param request The request
     * @return The resources matched, or {@code null} if the request was not routed to a resource
     */
    public static @Nullable JaxRsMatched get(HttpRequest<?> request) {
        return request.getAttribute(ATTRIBUTE, JaxRsMatched.class).orElse(null);
    }

    /**
     * @return The matched resources, the current one first
     */
    public List<Object> resources() {
        return reversed(resources);
    }

    /**
     * @return The number of path segments matched by each resource, the current one first
     */
    public List<Integer> segments() {
        return reversed(segments);
    }

    /**
     * @return The sub-resource locators that matched, the first one first
     */
    public List<Locator> locators() {
        return Collections.unmodifiableList(locators);
    }

    /**
     * @param pathVariables The path variables a parameter is read from
     * @return The locator that matched them, {@code null} if they are not the ones of a locator
     */
    @Nullable Locator locator(PathVariables pathVariables) {
        for (Locator locator : locators) {
            if (locator.variables == pathVariables) {
                return locator;
            }
        }
        return null;
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> result = new ArrayList<>(list);
        Collections.reverse(result);
        return result;
    }

    /**
     * A sub-resource locator that matched, and the provenance of its variables.
     *
     * @param variables The path variables of the locator
     * @param captures  The captures of its locator route: the variables of its prefix, then the
     *                  rest of the path, {@code null} if not known
     * @param end       The number of path segments matched up to its end, negative if unknown
     */
    public record Locator(PathVariables variables, @Nullable RouteCaptures captures, int end) {

        /**
         * @return The keys of the occurrences of the variables of its prefix: the names of the
         * variables of its template, see {@link JaxRsRouteTemplateEngine#repeated}
         */
        public List<String> keys() {
            if (captures == null) {
                return List.of();
            }
            List<RouteTemplateVariable> variables = captures.variables();
            // the last one is the rest of the path
            return variables.subList(0, Math.max(0, variables.size() - 1)).stream().map(RouteTemplateVariable::name).toList();
        }

        /**
         * @param key The key of an occurrence of a variable
         * @return The index of its first path segment and the number of its segments, from the base URI,
         * {@code null} if not known
         */
        public int @Nullable [] segments(String key) {
            if (captures == null || end < 0 || !captures.hasOffsets()) {
                return null;
            }
            int rest = captures.variables().size() - 1;
            int index = rest - 1;
            while (index >= 0 && !captures.variables().get(index).name().equals(key)) {
                index--;
            }
            int start = index < 0 ? -1 : captures.start(index);
            if (start < 0) {
                return null;
            }
            String path = captures.path();
            // the prefix ends before the slash the rest follows
            int prefixEnd = captures.start(rest) < 0 ? path.length() : captures.start(rest) - 1;
            int first = end - 1 - slashes(path, start, prefixEnd);
            return first < 0 ? null : new int[] {first, 1 + slashes(path, start, captures.end(index))};
        }

        private static int slashes(String path, int from, int to) {
            int slashes = 0;
            for (int i = from; i < to; i++) {
                if (path.charAt(i) == '/') {
                    slashes++;
                }
            }
            return slashes;
        }
    }
}
