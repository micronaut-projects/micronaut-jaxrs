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
import io.micronaut.web.router.builder.LocatedHttpRouteBuilder;
import io.micronaut.web.router.builder.LocatedHttpRouteGroup;
import io.micronaut.web.router.builder.LocatedRoutes;

/**
 * The routes of a class as the target of a sub-resource locator known only at runtime, relative
 * to the prefix of the locator. Generated for every class with resource methods or locators.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public interface JaxRsLocatedRoutes extends LocatedRoutes<Object> {

    /**
     * The routes of a class without routes.
     */
    JaxRsLocatedRoutes NONE = new JaxRsLocatedRoutes() {
        @Override
        public Class<?> type() {
            return Object.class;
        }

        @Override
        public void routes(LocatedHttpRouteBuilder<Object> routes) {
            // no routes: the request is not found
        }

        @Override
        public void declare(LocatedHttpRouteGroup<Object> group) {
            // no routes
        }
    };

    /**
     * @return The class whose instances the routes are for
     */
    Class<?> type();

    /**
     * Declare the routes in the group of the templates and the route selection of JAX-RS, see
     * {@link JaxRsRouteSupport#locatedRoutes(LocatedHttpRouteBuilder, JaxRsLocatedRoutes)}.
     *
     * @param group The group
     */
    void declare(LocatedHttpRouteGroup<Object> group);

    @Override
    default Argument<Object> targetType() {
        // a located target of any class: the routes are chosen by its class, see JaxRsRouteSupport#locatedRoutes
        return Argument.OBJECT_ARGUMENT;
    }
}
