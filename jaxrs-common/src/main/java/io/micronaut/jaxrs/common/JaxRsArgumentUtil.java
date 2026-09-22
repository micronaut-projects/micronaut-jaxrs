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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.reflection.ReflectionArguments;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.GenericType;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

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
        Argument<InvocationCallback> invocationCallbackArgument = ReflectionArguments.resolveGenericToArgument(
            callback.getClass(),
            InvocationCallback.class);
        Objects.requireNonNull(invocationCallbackArgument, "InvocationCallback argument cannot be null");
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
        List<Annotation> annotations = new ArrayList<>();
        for (String name : metadata.getAnnotationNames()) {
            AnnotationValue<Annotation> value = metadata.getAnnotation(name);
            if (value == null) {
                continue;
            }
            try {
                annotations.add(ReflectionAnnotations.synthesize(value, classLoader));
            } catch (IllegalArgumentException e) {
                // not an annotation the provider can see
            }
        }
        return annotations.toArray(Annotation[]::new);
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
        List<Annotation> real = new ArrayList<>(annotations.length);
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() != null) {
                // a fake annotation of a test has no type
                real.add(annotation);
            }
        }
        return ReflectionAnnotations.metadataOf(real.toArray(Annotation[]::new));
    }

}
