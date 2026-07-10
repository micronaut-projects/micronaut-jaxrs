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
 * Annotation metadata member names for Jakarta REST resource templates.
 */
@Internal
public final class JaxRsResourceTemplateMetadata {

    public static final String MEMBER_ROOT_PATH_SEGMENT_COUNT = "rootPathSegmentCount";
    public static final String MEMBER_ROOT_TEMPLATE = "rootTemplate";
    public static final String MEMBER_ROOT_CLASS_NAME = "rootClassName";
    public static final String MEMBER_HTTP_METHOD = "httpMethod";
    public static final String MEMBER_MATRIX_ROUTE_VARIABLE_NAMES = "matrixRouteVariableNames";
    public static final String MEMBER_PATH_SEGMENT_COUNT = "pathSegmentCount";
    public static final String MEMBER_PATH_PARAMETER_NAMES = "pathParameterNames";
    public static final String MEMBER_PATH_PARAMETER_SEGMENT_INDEXES = "pathParameterSegmentIndexes";
    public static final String MEMBER_LITERAL_CHARACTERS = "literalCharacters";
    public static final String MEMBER_CAPTURING_GROUPS = "capturingGroups";
    public static final String MEMBER_NON_DEFAULT_CAPTURING_GROUPS = "nonDefaultCapturingGroups";
    public static final String MEMBER_ROOT_LITERAL_CHARACTERS = "rootLiteralCharacters";
    public static final String MEMBER_ROOT_CAPTURING_GROUPS = "rootCapturingGroups";
    public static final String MEMBER_ROOT_NON_DEFAULT_CAPTURING_GROUPS = "rootNonDefaultCapturingGroups";

    private JaxRsResourceTemplateMetadata() {
    }
}
