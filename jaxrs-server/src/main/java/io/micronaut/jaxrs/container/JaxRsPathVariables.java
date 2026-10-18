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

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.PathVariables;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.UriRouteMatch;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The path variables of the route of a request, converted with the conversion service.
 *
 * @param values            The values of the variables, by name
 * @param rawValues         The values of the variables as they appear in the path, not percent-decoded, by name
 * @param conversionService The conversion service
 * @author Denis Stepanov
 * @since 5.2.0
 */
record JaxRsPathVariables(Map<String, Object> values, Map<String, Object> rawValues,
                          ConversionService conversionService) implements PathVariables {

    /**
     * @param request           The request
     * @param conversionService The conversion service
     * @return The path variables of the route the request matched, none if it matched none
     */
    static JaxRsPathVariables of(HttpRequest<?> request, ConversionService conversionService) {
        UriRouteMatch<?, ?> match = RouteAttributes.getRouteMatch(request)
            .filter(UriRouteMatch.class::isInstance)
            .<UriRouteMatch<?, ?>>map(m -> (UriRouteMatch<?, ?>) m)
            .orElse(null);
        if (match == null) {
            return new JaxRsPathVariables(Map.of(), Map.of(), conversionService);
        }
        return new JaxRsPathVariables(match.getVariableValues(), match.getRawVariableValues(), conversionService);
    }

    @Override
    public PathVariables raw() {
        // the raw variables of the raw variables are themselves
        return values == rawValues ? this : new JaxRsPathVariables(rawValues, rawValues, conversionService);
    }

    @Override
    public Set<String> names() {
        return values.keySet();
    }

    @Override
    public boolean contains(String name) {
        return values.containsKey(name);
    }

    @Override
    public <T> T get(String name, Argument<T> type) {
        return find(name, type).orElseThrow(() -> new IllegalArgumentException("No path variable [" + name + "] of type " + type));
    }

    @Override
    public <T> Optional<T> find(String name, Argument<T> type) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        ArgumentConversionContext<T> context = ConversionContext.of(type);
        return conversionService.convert(value, context);
    }
}
