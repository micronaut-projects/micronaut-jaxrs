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
 * Internal index of resource methods visible to dynamic Jakarta REST subresource locators.
 * <p>
 * Array members are positional: values at the same index describe the same
 * locator or resource method. Flattened members are expanded with their
 * corresponding {@code *Counts()} member.
 */
@Internal
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JaxRsDynamicSubResourceIndex {

    /**
     * @return Ordered Java method names for indexed dynamic subresource locators and resource methods.
     */
    String[] methodNames() default {};

    /**
     * @return Flattened fully qualified argument type names for each indexed method,
     * where each method consumes the next {@link #argumentTypeCounts()} entries.
     */
    String[] argumentTypes() default {};

    /**
     * @return Number of entries in {@link #argumentTypes()} belonging to each indexed method.
     */
    int[] argumentTypeCounts() default {};

    /**
     * @return HTTP method names for indexed resource methods at the same positions as {@link #methodNames()},
     * or an empty string for locator methods.
     */
    String[] httpMethods() default {};

    /**
     * @return Flattened route path segments for each indexed method route, where
     * each method consumes the next {@link #routePathSegmentCounts()} entries.
     */
    String[] routePathSegments() default {};

    /**
     * @return Number of entries in {@link #routePathSegments()} belonging to each indexed method route.
     */
    int[] routePathSegmentCounts() default {};

    /**
     * @return Original Jakarta REST resource templates for indexed resource methods
     * at the same positions as {@link #methodNames()}, or an empty string for locator methods.
     */
    String[] resourceTemplates() default {};

    /**
     * @return Flattened consumed media types, where each method consumes the next
     * {@link #consumesCounts()} entries.
     */
    String[] consumes() default {};

    /**
     * @return Number of entries in {@link #consumes()} belonging to each indexed method.
     */
    int[] consumesCounts() default {};

    /**
     * @return Flattened produced media types, where each method consumes the next
     * {@link #producesCounts()} entries.
     */
    String[] produces() default {};

    /**
     * @return Number of entries in {@link #produces()} belonging to each indexed method.
     */
    int[] producesCounts() default {};

    /**
     * @return Flattened route score triples for each indexed method route: literal
     * characters, capturing groups, and non-default capturing groups, with one
     * three-int tuple at the same position as each {@link #methodNames()} entry.
     */
    int[] routeScores() default {};
}
