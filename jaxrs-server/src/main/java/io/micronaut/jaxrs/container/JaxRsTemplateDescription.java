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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The structure of a JAX-RS route template, parsed once: the occurrences of its variables, with
 * the slashes of the template before each of them, and the slashes of the whole template. The
 * template of a route keeps its description, see {@link JaxRsRouteTemplateEngine#description}.
 *
 * @param expression  The template, without the marks of the route template engine
 * @param occurrences The occurrences of the variables, in their order in the template
 * @param slashes     The slashes of the template outside its variables
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
record JaxRsTemplateDescription(String expression, List<Occurrence> occurrences, int slashes) {

    /**
     * Parse a template: the regular expression of a variable can have braces.
     *
     * @param template The template, with or without the marks of the route template engine
     * @return Its description
     */
    static JaxRsTemplateDescription of(String template) {
        String expression = JaxRsRouteTemplateEngine.withoutMarks(template);
        List<Occurrence> occurrences = new ArrayList<>();
        int slashes = 0;
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (c == '/') {
                slashes++;
            } else if (c == '{') {
                // the end of the variable, its regular expression can have braces
                int depth = 1;
                int end = i + 1;
                while (end < expression.length() && depth > 0) {
                    char e = expression.charAt(end);
                    if (e == '{') {
                        depth++;
                    } else if (e == '}') {
                        depth--;
                    }
                    end++;
                }
                String variable = expression.substring(i + 1, Math.max(i + 1, end - 1));
                int colon = variable.indexOf(':');
                String name = (colon < 0 ? variable : variable.substring(0, colon)).trim();
                occurrences.add(new Occurrence(name, name, colon >= 0, slashes));
                i = end;
                continue;
            }
            i++;
        }
        // the key of each occurrence: the router names the earlier occurrences of a variable that repeats
        // from the last one, see JaxRsRouteTemplateEngine#repeated
        Map<String, Integer> total = new HashMap<>();
        for (Occurrence occurrence : occurrences) {
            total.merge(occurrence.name(), 1, Integer::sum);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<Occurrence> keyed = new ArrayList<>(occurrences.size());
        for (Occurrence occurrence : occurrences) {
            int fromLast = total.getOrDefault(occurrence.name(), 1) - seen.merge(occurrence.name(), 1, Integer::sum);
            String key = fromLast == 0 ? occurrence.name() : JaxRsRouteTemplateEngine.repeated(occurrence.name(), fromLast);
            keyed.add(new Occurrence(occurrence.name(), key, occurrence.regex(), occurrence.slashesBefore()));
        }
        return new JaxRsTemplateDescription(expression, List.copyOf(keyed), slashes);
    }

    /**
     * The provenance of the values a match of the template captured: the path segments each
     * occurrence of a variable matched, from the start of the template.
     *
     * @param rawValues The raw values of the match, by the key of each occurrence
     * @return The captures, in the order of the template
     */
    Captures captures(Function<String, @Nullable String> rawValues) {
        List<Capture> captures = new ArrayList<>(occurrences.size());
        int over = 0;
        for (Occurrence occurrence : occurrences) {
            String raw = rawValues.apply(occurrence.key());
            // a variable over several segments
            int slashesIn = raw == null ? 0 : (int) raw.chars().filter(ch -> ch == '/').count();
            captures.add(new Capture(occurrence.key(), occurrence.slashesBefore() + over, slashesIn + 1));
            over += slashesIn;
        }
        return new Captures(List.copyOf(captures), slashes + over);
    }

    /**
     * @return The names of the occurrences of the variables with a regular expression, in their
     * order in the template
     */
    List<String> regexVariables() {
        List<String> names = new ArrayList<>();
        for (Occurrence occurrence : occurrences) {
            if (occurrence.regex()) {
                names.add(occurrence.name());
            }
        }
        return List.copyOf(names);
    }

    /**
     * The path segments an occurrence of a variable matched.
     *
     * @param key   The key of the occurrence, see {@link Occurrence#key()}
     * @param first The index of its first segment from the start of the template
     * @param count The number of its segments
     */
    record Capture(String key, int first, int count) {
    }

    /**
     * The captures of a match.
     *
     * @param captures The captures, in the order of the template
     * @param slashes  The slashes of the matched path, the ones of the values included
     */
    record Captures(List<Capture> captures, int slashes) {

        /**
         * @param key The key of an occurrence
         * @return Its capture, {@code null} if the template has no such occurrence
         */
        @Nullable Capture capture(String key) {
            for (Capture capture : captures) {
                if (capture.key().equals(key)) {
                    return capture;
                }
            }
            return null;
        }
    }

    /**
     * An occurrence of a variable.
     *
     * @param name          The name of the variable
     * @param key           The name of the occurrence in the variables of a match: the name of the
     *                      variable for its last occurrence, see {@link JaxRsRouteTemplateEngine#repeated}
     * @param regex         Whether it has a regular expression
     * @param slashesBefore The slashes of the template before it, outside the variables
     */
    record Occurrence(String name, String key, boolean regex, int slashesBefore) {
    }
}
