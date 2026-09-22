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

    private static <T> List<T> reversed(List<T> list) {
        List<T> result = new ArrayList<>(list);
        Collections.reverse(result);
        return result;
    }
}
