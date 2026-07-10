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
 * Internal metadata for the original Jakarta REST resource template.
 */
@Internal
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JaxRsResourceTemplate {

    /**
     * @return The resource template relative to the Jakarta REST application path.
     */
    String value();

    /**
     * @return The number of URI path segments declared by the root resource class.
     */
    int rootPathSegmentCount() default -1;

    /**
     * @return The root resource class Jakarta REST template.
     */
    String rootTemplate() default "";

    /**
     * @return The root resource class name that owns the Jakarta REST route.
     */
    String rootClassName() default "";

    /**
     * @return The Jakarta REST HTTP method name resolved at build time.
     */
    String httpMethod() default "";

    /**
     * @return The route variable names that only exist to match optional matrix parameters.
     */
    String[] matrixRouteVariableNames() default {};

    /**
     * @return The number of URI path segments in the original Jakarta REST resource template.
     */
    int pathSegmentCount() default -1;

    /**
     * @return Path parameter names from the original Jakarta REST resource template.
     */
    String[] pathParameterNames() default {};

    /**
     * @return Segment indexes for each path parameter name.
     */
    int[] pathParameterSegmentIndexes() default {};

    /**
     * @return The number of literal characters in the original Jakarta REST resource template.
     */
    int literalCharacters() default -1;

    /**
     * @return The number of capturing groups in the original Jakarta REST resource template.
     */
    int capturingGroups() default -1;

    /**
     * @return The number of non-default capturing groups in the original Jakarta REST resource template.
     */
    int nonDefaultCapturingGroups() default -1;

    /**
     * @return The number of literal characters in the root resource class Jakarta REST template.
     */
    int rootLiteralCharacters() default -1;

    /**
     * @return The number of capturing groups in the root resource class Jakarta REST template.
     */
    int rootCapturingGroups() default -1;

    /**
     * @return The number of non-default capturing groups in the root resource class Jakarta REST template.
     */
    int rootNonDefaultCapturingGroups() default -1;
}
