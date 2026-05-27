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

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteInfo;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.filter.FilteredRouter;
import io.micronaut.web.router.filter.RouteMatchFilter;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Applies Jakarta REST route matching tie-breakers to JAX-RS routes.
 */
@Internal
@Singleton
final class JaxRsRouterListener implements BeanCreatedEventListener<Router> {

    @Override
    public Router onCreated(BeanCreatedEvent<Router> event) {
        Router router = event.getBean();
        return new FilteredRouter(router, new JaxRsRouteMatchFilter(router));
    }

    private static final class JaxRsRouteMatchFilter implements RouteMatchFilter {
        private final Router router;

        private JaxRsRouteMatchFilter(Router router) {
            this.router = router;
        }

        @Override
        public <T, R> Predicate<UriRouteMatch<T, R>> filter(HttpRequest<?> request) {
            List<UriRouteMatch<Object, Object>> closestMatches = router.findAllClosest(request);
            List<UriRouteMatch<Object, Object>> jaxRsMatches = closestMatches.stream()
                .filter(JaxRsRouteMatchFilter::isJaxRsRoute)
                .toList();
            if (jaxRsMatches.size() < 2) {
                return ignored -> true;
            }
            JaxRsRouteScore bestScore = jaxRsMatches.stream()
                .map(JaxRsRouteMatchFilter::score)
                .max(JaxRsRouteScore.COMPARATOR)
                .orElse(null);
            if (bestScore == null) {
                return ignored -> true;
            }
            Set<UriRouteInfo<?, ?>> selectedRoutes = jaxRsMatches.stream()
                .filter(match -> score(match).equals(bestScore))
                .map(UriRouteMatch::getRouteInfo)
                .collect(Collectors.toSet());
            if (selectedRoutes.size() == jaxRsMatches.size()) {
                return ignored -> true;
            }
            Set<UriRouteInfo<?, ?>> candidateRoutes = jaxRsMatches.stream()
                .map(UriRouteMatch::getRouteInfo)
                .collect(Collectors.toSet());
            return match -> !candidateRoutes.contains(match.getRouteInfo()) || selectedRoutes.contains(match.getRouteInfo());
        }

        private static boolean isJaxRsRoute(UriRouteMatch<?, ?> match) {
            return match.getRouteInfo().getAnnotationMetadata().hasAnnotation(Path.class);
        }

        private static JaxRsRouteScore score(UriRouteMatch<?, ?> match) {
            return JaxRsRouteScore.of(match.getRouteInfo().getUriMatchTemplate().toString());
        }
    }

    private record JaxRsRouteScore(int literalCharacters,
                                   int capturingGroups,
                                   int nonDefaultCapturingGroups) {
        private static final Comparator<JaxRsRouteScore> COMPARATOR = Comparator
            .comparingInt(JaxRsRouteScore::literalCharacters)
            .thenComparingInt(JaxRsRouteScore::capturingGroups)
            .thenComparingInt(JaxRsRouteScore::nonDefaultCapturingGroups);

        private static JaxRsRouteScore of(String template) {
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
}
