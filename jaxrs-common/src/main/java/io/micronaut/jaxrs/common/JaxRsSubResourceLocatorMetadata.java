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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;

/**
 * Annotation metadata member names for Jakarta REST subresource locators.
 */
@Internal
public final class JaxRsSubResourceLocatorMetadata {

    /**
     * Annotation member storing the declaring subresource type.
     */
    public static final String MEMBER_TYPE = "type";
    /**
     * Annotation member storing the matched route path for the locator.
     */
    public static final String MEMBER_ROUTE_PATH = "routePath";
    /**
     * Annotation member storing locator argument type names.
     */
    public static final String MEMBER_ARGUMENT_TYPES = "argumentTypes";
    /**
     * Annotation member storing the recursive locator method name.
     */
    public static final String MEMBER_RECURSIVE = "recursive";
    /**
     * Annotation member storing the remaining route variable name.
     */
    public static final String MEMBER_REMAINING = "remaining";
    /**
     * Annotation member storing candidate target method names.
     */
    public static final String MEMBER_TARGET_METHODS = "targetMethods";
    /**
     * Annotation member storing candidate target HTTP methods.
     */
    public static final String MEMBER_TARGET_HTTP_METHODS = "targetHttpMethods";
    /**
     * Annotation member storing candidate target resource templates.
     */
    public static final String MEMBER_TARGET_RESOURCE_TEMPLATES = "targetResourceTemplates";
    /**
     * Annotation member storing flattened candidate target path segments.
     */
    public static final String MEMBER_TARGET_PATH_SEGMENTS = "targetPathSegments";
    /**
     * Annotation member storing path segment counts for each candidate target.
     */
    public static final String MEMBER_TARGET_PATH_SEGMENT_COUNTS = "targetPathSegmentCounts";
    /**
     * Annotation member storing route scores for candidate targets.
     */
    public static final String MEMBER_TARGET_ROUTE_SCORES = "targetRouteScores";
    /**
     * Annotation member storing flattened target argument type names.
     */
    public static final String MEMBER_TARGET_ARGUMENT_TYPES = "targetArgumentTypes";
    /**
     * Annotation member storing argument type counts for each target method.
     */
    public static final String MEMBER_TARGET_ARGUMENT_TYPE_COUNTS = "targetArgumentTypeCounts";
    /**
     * Annotation member storing flattened target consumed media types.
     */
    public static final String MEMBER_TARGET_CONSUMES = "targetConsumes";
    /**
     * Annotation member storing consumed media type counts for each target method.
     */
    public static final String MEMBER_TARGET_CONSUMES_COUNTS = "targetConsumesCounts";
    /**
     * Annotation member storing flattened target produced media types.
     */
    public static final String MEMBER_TARGET_PRODUCES = "targetProduces";
    /**
     * Annotation member storing produced media type counts for each target method.
     */
    public static final String MEMBER_TARGET_PRODUCES_COUNTS = "targetProducesCounts";
    /**
     * Annotation member marking a locator that resolves targets dynamically.
     */
    public static final String MEMBER_DYNAMIC = "dynamic";
    /**
     * Route variable name used to capture the remaining dynamic subresource path.
     */
    public static final String DYNAMIC_REMAINING_ROUTE_VARIABLE = "jaxrsDynamicRemaining";
    /**
     * Dynamic index member storing method names.
     */
    public static final String DYNAMIC_INDEX_MEMBER_METHOD_NAMES = "methodNames";
    /**
     * Dynamic index member storing flattened argument type names.
     */
    public static final String DYNAMIC_INDEX_MEMBER_ARGUMENT_TYPES = "argumentTypes";
    /**
     * Dynamic index member storing argument type counts for each method.
     */
    public static final String DYNAMIC_INDEX_MEMBER_ARGUMENT_TYPE_COUNTS = "argumentTypeCounts";
    /**
     * Dynamic index member storing HTTP method names.
     */
    public static final String DYNAMIC_INDEX_MEMBER_HTTP_METHODS = "httpMethods";
    /**
     * Dynamic index member storing flattened route path segments.
     */
    public static final String DYNAMIC_INDEX_MEMBER_ROUTE_PATH_SEGMENTS = "routePathSegments";
    /**
     * Dynamic index member storing route path segment counts for each method.
     */
    public static final String DYNAMIC_INDEX_MEMBER_ROUTE_PATH_SEGMENT_COUNTS = "routePathSegmentCounts";
    /**
     * Dynamic index member storing resource templates.
     */
    public static final String DYNAMIC_INDEX_MEMBER_RESOURCE_TEMPLATES = "resourceTemplates";
    /**
     * Dynamic index member storing flattened consumed media types.
     */
    public static final String DYNAMIC_INDEX_MEMBER_CONSUMES = "consumes";
    /**
     * Dynamic index member storing consumed media type counts for each method.
     */
    public static final String DYNAMIC_INDEX_MEMBER_CONSUMES_COUNTS = "consumesCounts";
    /**
     * Dynamic index member storing flattened produced media types.
     */
    public static final String DYNAMIC_INDEX_MEMBER_PRODUCES = "produces";
    /**
     * Dynamic index member storing produced media type counts for each method.
     */
    public static final String DYNAMIC_INDEX_MEMBER_PRODUCES_COUNTS = "producesCounts";
    /**
     * Dynamic index member storing route scores for indexed methods.
     */
    public static final String DYNAMIC_INDEX_MEMBER_ROUTE_SCORES = "routeScores";

    private JaxRsSubResourceLocatorMetadata() {
    }
}
