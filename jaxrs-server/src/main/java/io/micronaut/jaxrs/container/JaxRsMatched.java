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
     * Record the path variables a sub-resource locator matched, with the path segments each one
     * captured: the router gives the variables of a located route only the last value of a
     * variable the locator and the route both capture.
     *
     * @param request       The request
     * @param pathVariables The path variables of the locator
     * @param description   The description of the template of the locator
     * @param end           The number of path segments matched up to the end of the locator, negative if unknown
     */
    static void addLocator(HttpRequest<?> request, PathVariables pathVariables, JaxRsTemplateDescription description, int end) {
        JaxRsMatched matched = get(request);
        if (matched == null) {
            matched = new JaxRsMatched();
            request.setAttribute(ATTRIBUTE, matched);
        }
        PathVariables raw = pathVariables.raw();
        matched.locators.add(new Locator(pathVariables, description.captures(key -> raw.findString(key).orElse(null)), end));
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
     * @param captures  The path segments each occurrence of a variable captured, from the start of its template
     * @param end       The number of path segments matched up to its end, negative if unknown
     */
    public record Locator(PathVariables variables, JaxRsTemplateDescription.Captures captures, int end) {

        /**
         * @return The keys of the occurrences of its variables, see {@link JaxRsTemplateDescription.Occurrence#key()}
         */
        public List<String> keys() {
            return captures.captures().stream().map(JaxRsTemplateDescription.Capture::key).toList();
        }

        /**
         * @param key The key of an occurrence of a variable
         * @return The index of its first path segment and the number of its segments, from the base URI,
         * {@code null} if not known
         */
        public int @Nullable [] segments(String key) {
            JaxRsTemplateDescription.Capture capture = captures.capture(key);
            if (capture == null || end < 0) {
                return null;
            }
            // the template starts with a slash, which precedes its first segment
            int first = end - captures.slashes() + capture.first() - 1;
            return first < 0 ? null : new int[] {first, capture.count()};
        }
    }
}
