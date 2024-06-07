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
import io.micronaut.http.annotation.Produces;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Maps the JAX-RS {@code Produces} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class ProducesMapper implements NamedAnnotationMapper {

    private static final String[] JAX_RS_DEFAULT_VALUE = { "*/*" };

    @NonNull
    @Override
    public String getName() {
        return "jakarta.ws.rs.Produces";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {

        final AnnotationValueBuilder<Produces> builder = AnnotationValue.builder(Produces.class);
        if (annotation.stringValues().length > 0) {
            builder.values(splitMediaTypes(annotation.stringValues()));
        } else {
            builder.values(JAX_RS_DEFAULT_VALUE);
        }
        return Collections.singletonList(
                builder.build()
        );
    }

    /**
     * JAX-RS allows literal commas inside the strings, we need to split those.
     */
    static String[] splitMediaTypes(String[] mediaTypes) {
        return Arrays.stream(mediaTypes)
            .flatMap(t -> Arrays.stream(t.split(",")))
            .map(String::trim)
            .toArray(String[]::new);
    }
}
