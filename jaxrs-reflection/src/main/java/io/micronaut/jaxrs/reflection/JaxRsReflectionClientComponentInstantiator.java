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
package io.micronaut.jaxrs.reflection;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.client.JaxRsClientComponentInstantiator;
import jakarta.ws.rs.core.Context;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reflection-backed fallback for Jakarta REST client components registered by class.
 */
@Internal
public final class JaxRsReflectionClientComponentInstantiator implements JaxRsClientComponentInstantiator {

    /**
     * Default constructor used by {@link java.util.ServiceLoader}.
     */
    public JaxRsReflectionClientComponentInstantiator() {
    }

    @Override
    public Optional<AnnotationMetadata> annotationMetadata(Class<?> componentClass) {
        Annotation[] annotations = componentClass.getAnnotations();
        if (annotations.length == 0) {
            return Optional.empty();
        }
        MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
        for (Annotation annotation : annotations) {
            Map<CharSequence, Object> values = new LinkedHashMap<>();
            Class<? extends Annotation> annotationType = annotation.annotationType();
            Method[] methods = annotationType.getMethods();
            for (Method method : methods) {
                if (!method.getDeclaringClass().equals(annotationType)) {
                    continue;
                }
                Object value = ReflectionUtils.invokeMethod(annotation, method);
                if (value != null) {
                    values.put(method.getName(), value);
                }
            }
            mutableAnnotationMetadata.addAnnotation(annotationType.getName(), values);
        }
        return Optional.of(mutableAnnotationMetadata);
    }

    @Override
    public Optional<Object> instantiate(Class<?> componentClass, ContextResolver contextResolver) {
        Constructor<?> constructor;
        try {
            constructor = componentClass.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
        try {
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            Object instance = constructor.newInstance();
            injectContext(instance, contextResolver);
            return Optional.of(instance);
        } catch (InvocationTargetException e) {
            throw propagate(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate Jakarta REST client component " + componentClass.getName(), e);
        }
    }

    private static void injectContext(Object instance, ContextResolver contextResolver) {
        Class<?> type = instance.getClass();
        while (type != null && type != Object.class) {
            injectContextFields(instance, type, contextResolver);
            injectContextMethods(instance, type, contextResolver);
            type = type.getSuperclass();
        }
    }

    private static void injectContextFields(Object instance, Class<?> type, ContextResolver contextResolver) {
        for (Field field : type.getDeclaredFields()) {
            if (!field.isAnnotationPresent(Context.class) || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object value = contextResolver.resolve(field.getType());
            if (value != null) {
                try {
                    field.setAccessible(true);
                    field.set(instance, value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot inject Jakarta REST client context into field " + field, e);
                }
            }
        }
    }

    private static void injectContextMethods(Object instance, Class<?> type, ContextResolver contextResolver) {
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() == 0) {
                continue;
            }
            Object[] values = contextValues(method, contextResolver);
            if (values.length == 0) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(instance, values);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot inject Jakarta REST client context into method " + method, e);
            }
        }
    }

    private static Object[] contextValues(Method method, ContextResolver contextResolver) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        boolean methodContext = method.isAnnotationPresent(Context.class);
        Object[] values = new Object[method.getParameterCount()];
        for (int i = 0; i < values.length; i++) {
            if (!methodContext && !hasContext(parameterAnnotations[i])) {
                return new Object[0];
            }
            Object value = contextResolver.resolve(method.getParameterTypes()[i]);
            if (value == null) {
                return new Object[0];
            }
            values[i] = value;
        }
        return values;
    }

    private static boolean hasContext(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() == Context.class) {
                return true;
            }
        }
        return false;
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(throwable);
    }
}
