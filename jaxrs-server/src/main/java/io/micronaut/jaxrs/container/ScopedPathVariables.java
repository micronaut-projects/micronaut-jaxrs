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
import io.micronaut.core.type.Argument;
import io.micronaut.http.PathVariables;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The path variables of a request as the prefix of a template sees them, e.g. the {@code @Path} of
 * a root resource class: a variable that repeats later in the template has the value of its
 * occurrences in the prefix, as the values of a resource are the ones of its own path, so for
 * {@code scope/{id}} and a method {@code {id}}, the {@code id} of the resource is the first one.
 * The occurrences of a variable that repeats are named from the last one, see
 * {@link JaxRsRouteTemplateEngine#repeated(String, int)}.
 *
 * @param delegate The variables of the matched route
 * @param shifts   For each variable of the prefix that repeats after it, the number of its
 *                 occurrences after the prefix
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
record ScopedPathVariables(PathVariables delegate, Map<String, Integer> shifts) implements PathVariables {

    /**
     * The variables of a template, the number of occurrences of each one.
     *
     * @param template The template
     * @return The occurrences of each variable
     */
    static Map<String, Integer> occurrences(String template) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '{') {
                if (depth++ == 0) {
                    start = i + 1;
                }
            } else if (c == '}' && --depth == 0) {
                String variable = template.substring(start, i);
                int colon = variable.indexOf(':');
                occurrences.merge((colon < 0 ? variable : variable.substring(0, colon)).trim(), 1, Integer::sum);
            }
        }
        return occurrences;
    }

    /**
     * The variables as a prefix of the template of the matched route sees them.
     *
     * @param pathVariables The variables of the matched route
     * @param occurrences   The occurrences of the variables of the prefix, see {@link #occurrences}
     * @return The variables, the same instance if no variable of the prefix repeats after it
     */
    static PathVariables of(PathVariables pathVariables, Map<String, Integer> occurrences) {
        if (occurrences.isEmpty()) {
            return pathVariables;
        }
        Map<String, Integer> shifts = null;
        for (Map.Entry<String, Integer> entry : occurrences.entrySet()) {
            String name = entry.getKey();
            int total = 1;
            while (pathVariables.contains(JaxRsRouteTemplateEngine.repeated(name, total))) {
                total++;
            }
            int shift = total - entry.getValue();
            if (shift > 0) {
                if (shifts == null) {
                    shifts = new LinkedHashMap<>();
                }
                shifts.put(name, shift);
            }
        }
        return shifts == null ? pathVariables : new ScopedPathVariables(pathVariables, shifts);
    }

    private String name(String name) {
        String base = name;
        int fromLast = 0;
        int mark = name.lastIndexOf(JaxRsRouteTemplateEngine.LOCATOR_MARK);
        if (mark > 0) {
            try {
                fromLast = Integer.parseInt(name.substring(mark + 1));
                base = name.substring(0, mark);
            } catch (NumberFormatException e) {
                return name;
            }
        }
        Integer shift = shifts.get(base);
        return shift == null ? name : JaxRsRouteTemplateEngine.repeated(base, fromLast + shift);
    }

    @Override
    public Set<String> names() {
        return delegate.names();
    }

    @Override
    public boolean contains(String name) {
        return delegate.contains(name(name));
    }

    @Override
    public <T> T get(String name, Argument<T> type) {
        return delegate.get(name(name), type);
    }

    @Override
    public <T> Optional<T> find(String name, Argument<T> type) {
        return delegate.find(name(name), type);
    }

    @Override
    public PathVariables raw() {
        return new ScopedPathVariables(delegate.raw(), shifts);
    }
}
