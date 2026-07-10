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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * Reflection-backed Jakarta REST parameter conversion fallback.
 */
@Internal
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
final class JaxRsReflectionParamConverterProvider implements ParamConverterProvider {
    private static final Set<Class<?>> MICRONAUT_CONVERTED_TYPES = Set.of(
        Boolean.class,
        Byte.class,
        Short.class,
        Integer.class,
        Long.class,
        Float.class,
        Double.class,
        Character.class
    );

    @Override
    @SuppressWarnings("unchecked")
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        return findConverter(rawType);
    }

    @SuppressWarnings("unchecked")
    static <T> @Nullable ParamConverter<T> findConverter(Class<T> rawType) {
        if (rawType.isPrimitive() || rawType == String.class || MICRONAUT_CONVERTED_TYPES.contains(rawType)) {
            return null;
        }
        if (rawType.isEnum()) {
            Method fromString = findStringFactory(rawType, "fromString");
            return fromString == null ? null : new ReflectionParamConverter<>(value -> (T) fromString.invoke(null, value));
        }
        Constructor<T> constructor = findStringConstructor(rawType);
        if (constructor != null) {
            return new ReflectionParamConverter<>(value -> constructor.newInstance(value));
        }
        Method valueOf = findStringFactory(rawType, "valueOf");
        if (valueOf != null) {
            return new ReflectionParamConverter<>(value -> (T) valueOf.invoke(null, value));
        }
        Method fromString = findStringFactory(rawType, "fromString");
        if (fromString != null) {
            return new ReflectionParamConverter<>(value -> (T) fromString.invoke(null, value));
        }
        return null;
    }

    private static <T> Constructor<T> findStringConstructor(Class<T> rawType) {
        try {
            return rawType.getConstructor(String.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Method findStringFactory(Class<?> rawType, String name) {
        try {
            Method method = rawType.getMethod(name, String.class);
            if (Modifier.isStatic(method.getModifiers()) && rawType.isAssignableFrom(method.getReturnType())) {
                return method;
            }
        } catch (NoSuchMethodException e) {
            return null;
        }
        return null;
    }

    private interface ReflectiveCreator<T> {
        T create(String value) throws ReflectiveOperationException;
    }

    private record ReflectionParamConverter<T>(ReflectiveCreator<T> creator) implements ParamConverter<T> {

        @Override
        public T fromString(String value) {
            try {
                return creator.create(value);
            } catch (InvocationTargetException e) {
                throw propagate(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public String toString(T value) {
            return value == null ? null : value.toString();
        }

        private static RuntimeException propagate(Throwable throwable) {
            if (throwable instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            return new IllegalArgumentException(throwable);
        }
    }
}
