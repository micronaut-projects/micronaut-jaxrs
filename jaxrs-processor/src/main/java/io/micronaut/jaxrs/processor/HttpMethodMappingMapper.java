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
package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.NonNull;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Maps the {@link HttpMethodMapping} the JAX-RS HTTP methods and {@code @Path} map to, to
 * {@code io.micronaut.jaxrs.container.JaxRsResourceMethod}: an executable resource method or
 * sub-resource locator, which the runtime routes of the JAX-RS server process on startup. The
 * methods of a controller are routed as a controller.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public class HttpMethodMappingMapper implements NamedAnnotationMapper {

    /**
     * The name of the annotation of a resource method.
     */
    static final String RESOURCE_METHOD = "io.micronaut.jaxrs.container.JaxRsResourceMethod";

    @NonNull
    @Override
    public String getName() {
        return HttpMethodMapping.class.getName();
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        return List.of(AnnotationValue.builder(RESOURCE_METHOD).build());
    }
}
