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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.PathVariables;
import io.micronaut.http.bind.MatchedPathVariables;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.UriRouteMatch;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ResourceContext;

import java.util.Map;

/**
 * The {@link ResourceContext} injected with {@code @Context}: in a request, resources are created and
 * initialized with the values of the request like the classes sub-resource locators return, and
 * outside of one they are the beans of the application, and other classes are instantiated and
 * injected like beans.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsContextResourceContext implements ResourceContext {

    private final BeanContext beanContext;
    private final BeanProvider<JaxRsRuntimeRoutes> routes;
    private final ConversionService conversionService;

    JaxRsContextResourceContext(BeanContext beanContext, BeanProvider<JaxRsRuntimeRoutes> routes, ConversionService conversionService) {
        this.beanContext = beanContext;
        this.routes = routes;
        this.conversionService = conversionService;
    }

    @Override
    public <T> T getResource(Class<T> resourceClass) {
        HttpRequest<?> request = ServerRequestContext.currentRequest().orElse(null);
        if (request == null) {
            return beanContext.findBean(resourceClass)
                .orElseGet(() -> beanContext.inject(JaxRsReflection.get().instantiate(resourceClass)));
        }
        return routes.get().resource(resourceClass, request, pathVariables(request));
    }

    @Override
    public <T> T initResource(T resource) {
        HttpRequest<?> request = ServerRequestContext.currentRequest().orElse(null);
        if (request == null) {
            return beanContext.inject(resource);
        }
        return routes.get().initResource(resource, request, pathVariables(request));
    }

    /**
     * The path variables of the route the request matched, none if it matched none: only their
     * strings are read, converted by the JAX-RS parameter readers.
     */
    private PathVariables pathVariables(HttpRequest<?> request) {
        UriRouteMatch<?, ?> match = RouteAttributes.getRouteMatch(request)
            .filter(UriRouteMatch.class::isInstance)
            .<UriRouteMatch<?, ?>>map(m -> (UriRouteMatch<?, ?>) m)
            .orElse(null);
        if (match == null) {
            return new MatchedPathVariables(Map.of(), conversionService);
        }
        return new MatchedPathVariables(match.getVariableValues(), match.getRawVariableValues(), conversionService);
    }
}
