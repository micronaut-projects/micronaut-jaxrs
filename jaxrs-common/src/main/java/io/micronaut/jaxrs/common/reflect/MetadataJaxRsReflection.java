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
package io.micronaut.jaxrs.common.reflect;

import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@link JaxRsReflection} of the metadata the annotation processors generate, without
 * reflection.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class MetadataJaxRsReflection implements JaxRsReflection {

    private static final String MICRONAUT_ANNOTATIONS = "io.micronaut.";

    @Override
    public boolean isReflective() {
        return false;
    }

    @Override
    public AnnotationMetadata annotationMetadata(Class<?> type) {
        return BeanIntrospector.SHARED.findIntrospection(type)
            .<AnnotationMetadata>map(AnnotationMetadataProvider::getAnnotationMetadata)
            .orElse(AnnotationMetadata.EMPTY_METADATA);
    }

    @Override
    public AnnotationMetadata annotationMetadata(Method method) {
        throw new UnsupportedOperationException(unsupported("The annotations of the method " + method));
    }

    @Override
    public AnnotationMetadata annotationMetadata(Annotation[] annotations) {
        if (annotations.length == 0) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        // the members of the annotations Micronaut synthesized, else only the types: the members of
        // other annotations are read with reflection
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> type = annotation.annotationType();
            if (annotation instanceof AnnotationValueProvider<?> provider) {
                metadata.addDeclaredAnnotation(type.getName(), provider.annotationValue().getValues());
            } else if (type != null) {
                metadata.addDeclaredAnnotation(type.getName(), Map.of());
            }
        }
        return metadata;
    }

    @Override
    public Annotation[] annotations(AnnotationMetadata metadata, ClassLoader classLoader) {
        return metadata.synthesizeAll();
    }

    @Override
    public AnnotationMetadata methodAnnotations(ExecutableMethod<?, ?> method) {
        AnnotationMetadata metadata = method.getAnnotationMetadata();
        AnnotationMetadata methodMetadata = metadata instanceof AnnotationMetadataHierarchy hierarchy ? hierarchy.getDeclaredMetadata() : metadata;
        // the annotations of the application, without the ones the processors map them to
        MutableAnnotationMetadata annotations = new MutableAnnotationMetadata();
        for (String name : methodMetadata.getAnnotationNames()) {
            if (!name.startsWith(MICRONAUT_ANNOTATIONS)) {
                AnnotationValue<Annotation> value = methodMetadata.getAnnotation(name);
                annotations.addDeclaredAnnotation(name, value == null ? Map.of() : value.getValues());
            }
        }
        return annotations;
    }

    @Override
    public Annotation[] annotations(ExecutableMethod<?, ?> method) {
        return methodAnnotations(method).synthesizeAll();
    }

    @Override
    public Method targetMethod(ExecutableMethod<?, ?> method) {
        throw new UnsupportedOperationException(unsupported("The java.lang.reflect.Method of the resource method "
            + method.getDeclaringType().getName() + "#" + method.getMethodName()));
    }

    @Override
    public @Nullable Argument<?> resolveGeneric(Class<?> type, Class<?> genericType) {
        return null;
    }

    @Override
    public <T> @Nullable BeanIntrospection<T> introspection(Class<T> type) {
        return BeanIntrospector.SHARED.findIntrospection(type).orElse(null);
    }

    @Override
    public <T> T instantiate(Class<T> type) {
        BeanIntrospection<T> introspection = introspection(type);
        if (introspection == null) {
            throw new IllegalStateException(unsupported("An instance of the class " + type.getName()
                + ", which is not a bean nor introspected,"));
        }
        return introspection.instantiate();
    }

    @Override
    public @Nullable Function<String, Object> stringConversion(Class<?> type) {
        BeanIntrospection<?> introspection = introspection(type);
        if (introspection == null) {
            return null;
        }
        Argument<?>[] arguments = introspection.getConstructorArguments();
        if (arguments.length == 1 && arguments[0].getType() == String.class) {
            return introspection::instantiate;
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void injectFields(Object instance, Class<? extends Annotation> annotation, Function<Class<?>, @Nullable Object> values) {
        BeanIntrospection<Object> introspection = (BeanIntrospection<Object>) introspection(instance.getClass());
        if (introspection == null) {
            return;
        }
        List<BeanProperty<Object, Object>> properties = new ArrayList<>();
        for (BeanProperty<Object, Object> property : introspection.getBeanProperties()) {
            if (property.hasAnnotation(annotation) && !property.isReadOnly()) {
                properties.add(property);
            }
        }
        for (BeanProperty<Object, Object> property : properties) {
            Object value = values.apply(property.getType());
            if (value != null && (property.isWriteOnly() || property.get(instance) == null)) {
                property.set(instance, value);
            }
        }
    }

    @Override
    public <T> @Nullable RuntimeBeanDefinition<T> beanDefinition(Class<T> type) {
        return null;
    }

    private static String unsupported(String what) {
        return what + " needs reflection: add io.micronaut:micronaut-reflection to the runtime classpath";
    }
}
