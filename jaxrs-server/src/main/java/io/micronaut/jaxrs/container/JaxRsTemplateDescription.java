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

import java.util.ArrayList;
import java.util.List;

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
                occurrences.add(new Occurrence(name, colon >= 0, slashes));
                i = end;
                continue;
            }
            i++;
        }
        return new JaxRsTemplateDescription(expression, List.copyOf(occurrences), slashes);
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
     * An occurrence of a variable.
     *
     * @param name          The name of the variable
     * @param regex         Whether it has a regular expression
     * @param slashesBefore The slashes of the template before it, outside the variables
     */
    record Occurrence(String name, boolean regex, int slashesBefore) {
    }
}
