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

import java.util.Comparator;

/**
 * Jakarta REST request matching score for a route URI template.
 */
record JaxRsRouteScore(int literalCharacters,
                       int capturingGroups,
                       int nonDefaultCapturingGroups) {

    static final Comparator<JaxRsRouteScore> COMPARATOR = Comparator
        .comparingInt(JaxRsRouteScore::literalCharacters)
        .thenComparingInt(JaxRsRouteScore::capturingGroups)
        .thenComparingInt(JaxRsRouteScore::nonDefaultCapturingGroups);

    static JaxRsRouteScore of(String template) {
        int literalCharacters = 0;
        int capturingGroups = 0;
        int nonDefaultCapturingGroups = 0;
        int braceDepth = 0;
        boolean variableHasRegex = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '{') {
                if (braceDepth == 0) {
                    capturingGroups++;
                    variableHasRegex = false;
                }
                braceDepth++;
            } else if (c == '}' && braceDepth > 0) {
                braceDepth--;
                if (braceDepth == 0 && variableHasRegex) {
                    nonDefaultCapturingGroups++;
                }
            } else if (braceDepth == 0) {
                literalCharacters++;
            } else if (braceDepth == 1 && c == ':') {
                variableHasRegex = true;
            }
        }
        return new JaxRsRouteScore(literalCharacters, capturingGroups, nonDefaultCapturingGroups);
    }
}
