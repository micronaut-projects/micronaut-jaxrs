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
package io.micronaut.jaxrs.common;

import io.micronaut.context.AnnotationReflectionUtils;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.GenericType;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * An argument util class.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class ArgumentUtil {

    private ArgumentUtil() {
    }

    public static <T> Argument<T> from(InvocationCallback<T> callback) {
        return AnnotationReflectionUtils.resolveGenericToArgument(callback.getClass(), InvocationCallback.class).getTypeParameters()[0];
    }

    public static <T> Argument<T> from(Entity<T> entityType) {
        Argument<T> argument = (Argument<T>) Argument.of(entityType.getEntity().getClass());
        return Argument.of(argument.getType(), createAnnotationMetadata(entityType.getAnnotations()), argument.getTypeParameters());
    }

    public static <T> Argument<T> from(GenericType<T> entityType, Annotation[] annotations) {
        AnnotationMetadata annotationMetadata = createAnnotationMetadata(annotations);
        Argument<T> argument = (Argument<T>) Argument.of(entityType.getType());
        return Argument.of(argument.getType(), annotationMetadata, argument.getTypeParameters());
    }

    public static <T> Argument<T> from(GenericType<T> entityType) {
        return (Argument<T>) Argument.of(entityType.getType());
    }

    public static <T> Argument<T> from(GenericEntity<T> genericEntity) {
        Argument<T> argument = (Argument<T>) Argument.of(genericEntity.getType());
        return Argument.of(argument.getType(), AnnotationMetadata.EMPTY_METADATA, argument.getTypeParameters());
    }

    public static <T> Argument<T> from(GenericEntity<T> genericEntity, Annotation[] annotations) {
        AnnotationMetadata annotationMetadata = createAnnotationMetadata(annotations);
        Argument<T> argument = (Argument<T>) Argument.of(genericEntity.getType());
        return Argument.of(argument.getType(), annotationMetadata, argument.getTypeParameters());
    }

    public static <T> Argument<T> from(Class<T> entityType, Annotation[] annotations) {
        return Argument.of(entityType, createAnnotationMetadata(annotations));
    }

    public static AnnotationMetadata createAnnotationMetadata(Annotation[] annotations) {
        if (annotations == null || annotations.length == 0) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() == null) {
                // Fake annotation workaround
                continue;
            }
            annotationMetadata.addAnnotation(annotation.annotationType().getName(), Map.of());
        }
        return annotationMetadata;
    }

}
