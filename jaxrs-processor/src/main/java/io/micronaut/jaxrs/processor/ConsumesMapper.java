/*
 * Copyright 2017-2024 original authors
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
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code Consumes} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class ConsumesMapper implements NamedAnnotationMapper {

    private static final String[] JAX_RS_DEFAULT_VALUE = {"*/*"};

    @NonNull
    @Override
    public String getName() {
        return "jakarta.ws.rs.Consumes";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {

        final AnnotationValueBuilder<Consumes> builder = AnnotationValue.builder(Consumes.class);
        if (annotation.stringValues().length > 0) {
            builder.values(ProducesMapper.splitMediaTypes(annotation.stringValues()));
        } else {
            builder.values(JAX_RS_DEFAULT_VALUE);
        }
        return Collections.singletonList(
            builder.build()
        );
    }
}
