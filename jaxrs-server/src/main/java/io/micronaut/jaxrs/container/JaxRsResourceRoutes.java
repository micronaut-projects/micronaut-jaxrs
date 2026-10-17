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
import io.micronaut.web.router.builder.HttpRouteGroup;
import io.micronaut.web.router.builder.HttpRoutes;

/**
 * The routes of a root resource class. Generated for every root resource class.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public interface JaxRsResourceRoutes extends HttpRoutes {

    /**
     * Declare the routes in the group of the templates and the route selection of JAX-RS, see
     * {@link JaxRsRouteSupport#routes(io.micronaut.web.router.builder.HttpRouteBuilder, JaxRsResourceRoutes)}.
     *
     * @param group The group
     */
    void declare(HttpRouteGroup group);
}
