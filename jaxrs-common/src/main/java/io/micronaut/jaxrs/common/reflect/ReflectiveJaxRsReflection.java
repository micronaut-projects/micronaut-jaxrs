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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.reflect.InstantiationUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.reflection.ReflectionArguments;
import io.micronaut.reflection.ReflectionBeanDefinition;
import io.micronaut.reflection.ReflectionBeanIntrospection;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@link JaxRsReflection} of the {@code micronaut-reflection} module: the service the
 * service loader finds when the module is on the classpath.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public final class ReflectiveJaxRsReflection implements JaxRsReflection {

    @Override
    public boolean isReflective() {
        return true;
    }

    @Override
    public AnnotationMetadata annotationMetadata(Class<?> type) {
        return ReflectionAnnotations.metadataOf(type);
    }

    @Override
    public AnnotationMetadata annotationMetadata(Method method) {
        return ReflectionAnnotations.metadataOf(method);
    }

    @Override
    public AnnotationMetadata annotationMetadata(Annotation[] annotations) {
        if (annotations.length == 0) {
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

    @Override
    public Annotation[] annotations(AnnotationMetadata metadata, ClassLoader classLoader) {
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

    @Override
    public AnnotationMetadata methodAnnotations(ExecutableMethod<?, ?> method) {
        return annotationMetadata(method.getTargetMethod().getAnnotations());
    }

    @Override
    public Annotation[] annotations(ExecutableMethod<?, ?> method) {
        return method.getTargetMethod().getAnnotations();
    }

    @Override
    public Method targetMethod(ExecutableMethod<?, ?> method) {
        return method.getTargetMethod();
    }

    @Override
    public @Nullable Argument<?> resolveGeneric(Class<?> type, Class<?> genericType) {
        return ReflectionArguments.resolveGenericToArgument(type, genericType);
    }

    @Override
    public <T> @Nullable BeanIntrospection<T> introspection(Class<T> type) {
        BeanIntrospection<T> introspection = BeanIntrospector.SHARED.findIntrospection(type).orElse(null);
        if (introspection != null) {
            return introspection;
        }
        if (!ReflectionBeanIntrospection.isIntrospectable(type)) {
            return null;
        }
        return ReflectionBeanIntrospection.of(type, Set.of(Introspected.AccessKind.FIELD, Introspected.AccessKind.METHOD));
    }

    @Override
    public <T> T instantiate(Class<T> type) {
        BeanIntrospection<T> introspection = BeanIntrospector.SHARED.findIntrospection(type).orElse(null);
        if (introspection != null) {
            return introspection.instantiate();
        }
        // a reflective introspection reaches a class or constructor that is not public
        return ReflectionBeanIntrospection.isIntrospectable(type)
            ? ReflectionBeanIntrospection.of(type).instantiate()
            : InstantiationUtils.instantiate(type);
    }

    @Override
    public @Nullable Function<String, Object> stringConversion(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || ClassUtils.isJavaLangType(type)) {
            return null;
        }
        // valueOf before fromString, except for an enum
        for (String factory : type.isEnum() ? new String[]{"fromString", "valueOf"} : new String[]{"valueOf", "fromString"}) {
            Method method = ReflectionUtils.findMethod(type, factory, String.class).orElse(null);
            if (method != null && Modifier.isStatic(method.getModifiers()) && type.isAssignableFrom(method.getReturnType())) {
                return value -> invoke(() -> method.invoke(null, value));
            }
        }
        Constructor<?> constructor = ReflectionUtils.findConstructor(type, String.class).orElse(null);
        if (constructor != null) {
            return value -> invoke(() -> constructor.newInstance(value));
        }
        return null;
    }

    private static Object invoke(ReflectiveCall call) {
        try {
            return call.call();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalArgumentException(cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void injectFields(Object instance, Class<? extends Annotation> annotation, Function<Class<?>, @Nullable Object> values) {
        for (Class<?> type = instance.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(annotation) || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value = values.apply(field.getType());
                if (value != null) {
                    try {
                        field.setAccessible(true);
                        if (field.get(instance) == null) {
                            field.set(instance, value);
                        }
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // a field that cannot be set is left as it is
                    }
                }
            }
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> @Nullable RuntimeBeanDefinition<T> beanDefinition(Class<T> type) {
        if (!ReflectionBeanDefinition.isDefinable(type)) {
            return null;
        }
        // found by each of its types: a runtime definition is only indexed by the types it exposes
        return ReflectionBeanDefinition.builder((Class) type).exposedTypes(supertypes(type)).build();
    }

    /**
     * A class and every class and interface it extends or implements, but {@code Object}.
     */
    private static Class<?>[] supertypes(Class<?> type) {
        Set<Class<?>> types = new LinkedHashSet<>();
        List<Class<?>> pending = new ArrayList<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> next = pending.remove(pending.size() - 1);
            if (next == null || next == Object.class || !types.add(next)) {
                continue;
            }
            pending.add(next.getSuperclass());
            pending.addAll(List.of(next.getInterfaces()));
        }
        return types.toArray(Class<?>[]::new);
    }

    /**
     * A reflective call.
     */
    @FunctionalInterface
    private interface ReflectiveCall {
        Object call() throws ReflectiveOperationException;
    }
}
