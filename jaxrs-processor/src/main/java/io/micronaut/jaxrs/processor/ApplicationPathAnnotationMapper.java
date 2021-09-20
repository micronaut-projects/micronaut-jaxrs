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

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

/**
 * Maps the JAX-RS {@code ApplicationPath} annotation turning the application class into a singleton.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Internal
public class ApplicationPathAnnotationMapper implements NamedAnnotationMapper {
    @NonNull
    @Override
    public String getName() {
        return "javax.ws.rs.ApplicationPath";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        final AnnotationValueBuilder<UriMapping> builder = AnnotationValue.builder(UriMapping.class);
        final String path = annotation.stringValue().orElse("/");
        builder.value(path);
        return Arrays.asList(
                AnnotationValue.builder(Singleton.class).build(),
                builder.build()
        );
    }
}
