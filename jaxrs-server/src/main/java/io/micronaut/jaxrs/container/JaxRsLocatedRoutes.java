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
import io.micronaut.web.router.builder.HttpRouteBuilder;

/**
 * The routes of a class as the target of a sub-resource locator known only at runtime, relative
 * to the prefix of the locator. Generated for every class with resource methods or locators.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public interface JaxRsLocatedRoutes {

    /**
     * @return The class whose instances the routes are for
     */
    Class<?> type();

    /**
     * Declare the routes, relative to the prefix of the locator.
     *
     * @param routes The builder
     */
    void routes(HttpRouteBuilder routes);
}
