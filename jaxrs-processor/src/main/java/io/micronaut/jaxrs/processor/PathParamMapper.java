/*
 * Copyright 2017-2020 original authors
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

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nonnull;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Maps the JAX-RS {@code PathParam} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
public class PathParamMapper implements NamedAnnotationMapper {
    @Nonnull
    @Override
    public String getName() {
        return "javax.ws.rs.PathParam";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {

        final Optional<String> annotationValue = annotation.stringValue();
        if (!annotationValue.isPresent()) {
            return Collections.singletonList(
                    AnnotationValue.builder(PathVariable.class).build()
            );
        }

        final String value = annotationValue.get();
        return Arrays.asList(
                AnnotationValue.builder(PathVariable.class).value(value).build(),
                AnnotationValue.builder(Bindable.class).value(value).build()
        );
    }
}
