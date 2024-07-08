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
package io.micronaut.jaxrs.processor.transformer;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Inherited;
import java.util.List;

/**
 * The validation annotations remapper.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsAnnotationRemapper implements AnnotationRemapper {

    @Override
    public String getPackageName() {
        return AnnotationRemapper.ALL_PACKAGES;
    }

    @Override
    public List<AnnotationValue<?>> remap(AnnotationValue<?> annotation, VisitorContext visitorContext) {
        if (annotation.getAnnotationName().startsWith("jakarta.ws.rs")) {
            return List.of(
                annotation.mutate().stereotype(
                    AnnotationValue.builder(Inherited.class).build()
                ).build());
        }
        return List.of(annotation);
    }
}
