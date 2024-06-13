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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.annotation.NamedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.UUID;

/**
 * Maps the JAX-RS {@code Provider} annotation.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
public class ProviderMapper implements NamedAnnotationMapper {

    @NonNull
    @Override
    public String getName() {
        return "jakarta.ws.rs.ext.Provider";
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        return List.of(
            AnnotationValue.builder(Singleton.class).build(),
            // Remove hack after https://github.com/micronaut-projects/micronaut-core/pull/10902
            AnnotationValue.builder(Named.class).value(UUID.randomUUID().toString()).build()
        );
    }
}
