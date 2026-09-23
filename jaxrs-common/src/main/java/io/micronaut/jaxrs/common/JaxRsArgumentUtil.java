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

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.GenericType;

import java.lang.annotation.Annotation;

/**
 * An argument util class.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsArgumentUtil {

    private JaxRsArgumentUtil() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> Argument<T> from(InvocationCallback<T> callback) {
        // the type argument of the callback, known only with reflection
        Argument<?> invocationCallbackArgument = JaxRsReflection.get().resolveGeneric(callback.getClass(), InvocationCallback.class);
        if (invocationCallbackArgument == null || invocationCallbackArgument.getTypeParameters().length == 0) {
            return (Argument<T>) Argument.OBJECT_ARGUMENT;
        }
        return (Argument<T>) invocationCallbackArgument.getTypeParameters()[0];
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

    /**
     * The annotations of metadata as instances for a provider of the application: their types are
     * the ones the class loader of the provider defines, as a provider compares them by identity,
     * e.g. {@code annotation.annotationType() == EntityAnnotation.class}. An annotation the loader
     * does not define is left out: the provider cannot refer to it.
     *
     * @param metadata The metadata
     * @param provider The provider the annotations are handed to
     * @return The annotations
     */
    public static Annotation[] annotations(AnnotationMetadata metadata, Object provider) {
        if (metadata.isEmpty()) {
            return new Annotation[0];
        }
        ClassLoader classLoader = provider.getClass().getClassLoader();
        if (classLoader == null) {
            return metadata.synthesizeAll();
        }
        return JaxRsReflection.get().annotations(metadata, classLoader);
    }

    /**
     * The metadata of the annotations JAX-RS hands over as an array, e.g. the ones of an entity: with
     * their members, defaults and stereotypes, like generated metadata.
     *
     * @param annotations The annotations
     * @return The metadata
     */
    public static AnnotationMetadata createAnnotationMetadata(Annotation[] annotations) {
        if (annotations == null || annotations.length == 0) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        return JaxRsReflection.get().annotationMetadata(annotations);
    }

}
