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
     * @return The resource method to invoke on the object returned by the locator.
     */
    String value();

    /**
     * @return The resource type that declares the target method.
     */
    Class<?> type();

    /**
     * @return The final route path to apply after core route validation.
     */
    String routePath() default "";

    /**
     * @return The target method argument type names.
     */
    String[] argumentTypes() default {};

    /**
     * @return Candidate target method names for dynamic subresource target selection.
     */
    String[] targetMethods() default {};

    /**
     * @return Candidate target HTTP method names for dynamic subresource target selection.
     */
    String[] targetHttpMethods() default {};

    /**
     * @return Candidate target resource templates for dynamic subresource target selection.
     */
    String[] targetResourceTemplates() default {};

    /**
     * @return Flattened candidate target method argument type names.
     */
    String[] targetArgumentTypes() default {};

    /**
     * @return Candidate target method argument type counts.
     */
    int[] targetArgumentTypeCounts() default {};

    /**
     * @return Flattened candidate target consumed media types.
     */
    String[] targetConsumes() default {};

    /**
     * @return Candidate target consumed media type counts.
     */
    int[] targetConsumesCounts() default {};

    /**
     * @return Flattened candidate target produced media types.
     */
    String[] targetProduces() default {};

    /**
     * @return Candidate target produced media type counts.
     */
    int[] targetProducesCounts() default {};

    /**
     * @return Whether target selection must be performed from the runtime locator result type.
     */
    boolean dynamic() default false;

    /**
     * @return The zero-argument recursive locator method to invoke for each remaining path segment.
     */
    String recursive() default "";

    /**
     * @return The route variable that captures the remaining recursive path.
     */
    String remaining() default "";
}
