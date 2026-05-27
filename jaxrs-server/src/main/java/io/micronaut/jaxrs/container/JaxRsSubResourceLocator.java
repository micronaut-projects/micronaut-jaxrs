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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Internal marker for Jakarta REST subresource locator routes.
 */
@Internal
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JaxRsSubResourceLocator {

    /**
     * @return The zero-argument resource method to invoke on the object returned by the locator.
     */
    String value();

    /**
     * @return The resource type that declares the target method.
     */
    Class<?> type();

    /**
     * @return The zero-argument recursive locator method to invoke for each remaining path segment.
     */
    String recursive() default "";

    /**
     * @return The route variable that captures the remaining recursive path.
     */
    String remaining() default "";
}
