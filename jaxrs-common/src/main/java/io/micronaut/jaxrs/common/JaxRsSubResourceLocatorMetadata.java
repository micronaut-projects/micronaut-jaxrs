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

    public static final String MEMBER_TYPE = "type";
    public static final String MEMBER_ROUTE_PATH = "routePath";
    public static final String MEMBER_ARGUMENT_TYPES = "argumentTypes";
    public static final String MEMBER_RECURSIVE = "recursive";
    public static final String MEMBER_REMAINING = "remaining";
    public static final String MEMBER_TARGET_METHODS = "targetMethods";
    public static final String MEMBER_TARGET_HTTP_METHODS = "targetHttpMethods";
    public static final String MEMBER_TARGET_RESOURCE_TEMPLATES = "targetResourceTemplates";
    public static final String MEMBER_TARGET_PATH_SEGMENTS = "targetPathSegments";
    public static final String MEMBER_TARGET_PATH_SEGMENT_COUNTS = "targetPathSegmentCounts";
    public static final String MEMBER_TARGET_ROUTE_SCORES = "targetRouteScores";
    public static final String MEMBER_TARGET_ARGUMENT_TYPES = "targetArgumentTypes";
    public static final String MEMBER_TARGET_ARGUMENT_TYPE_COUNTS = "targetArgumentTypeCounts";
    public static final String MEMBER_TARGET_CONSUMES = "targetConsumes";
    public static final String MEMBER_TARGET_CONSUMES_COUNTS = "targetConsumesCounts";
    public static final String MEMBER_TARGET_PRODUCES = "targetProduces";
    public static final String MEMBER_TARGET_PRODUCES_COUNTS = "targetProducesCounts";
    public static final String MEMBER_DYNAMIC = "dynamic";
    public static final String DYNAMIC_REMAINING_ROUTE_VARIABLE = "jaxrsDynamicRemaining";
    public static final String DYNAMIC_INDEX_MEMBER_METHOD_NAMES = "methodNames";
    public static final String DYNAMIC_INDEX_MEMBER_ARGUMENT_TYPES = "argumentTypes";
    public static final String DYNAMIC_INDEX_MEMBER_ARGUMENT_TYPE_COUNTS = "argumentTypeCounts";
    public static final String DYNAMIC_INDEX_MEMBER_HTTP_METHODS = "httpMethods";
    public static final String DYNAMIC_INDEX_MEMBER_ROUTE_PATH_SEGMENTS = "routePathSegments";
    public static final String DYNAMIC_INDEX_MEMBER_ROUTE_PATH_SEGMENT_COUNTS = "routePathSegmentCounts";
    public static final String DYNAMIC_INDEX_MEMBER_RESOURCE_TEMPLATES = "resourceTemplates";
    public static final String DYNAMIC_INDEX_MEMBER_CONSUMES = "consumes";
    public static final String DYNAMIC_INDEX_MEMBER_CONSUMES_COUNTS = "consumesCounts";
    public static final String DYNAMIC_INDEX_MEMBER_PRODUCES = "produces";
    public static final String DYNAMIC_INDEX_MEMBER_PRODUCES_COUNTS = "producesCounts";
    public static final String DYNAMIC_INDEX_MEMBER_ROUTE_SCORES = "routeScores";

    private JaxRsSubResourceLocatorMetadata() {
    }
}
