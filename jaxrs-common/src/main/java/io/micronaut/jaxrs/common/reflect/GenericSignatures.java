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

import io.micronaut.core.type.Argument;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The type arguments a class gives a generic type in its generic signature, e.g. the
 * {@code InvocationCallback<String>} of an anonymous callback of the application, which the
 * processors record no type hierarchy of.
 *
 * <p>The generic signature is class metadata of the JDK ({@link Class#getGenericInterfaces()},
 * {@link Class#getGenericSuperclass()}), not member access: it needs neither the
 * micronaut-reflection module nor, in a native image, a reflection configuration, as GraalVM
 * keeps the {@code Signature} attribute of the classes it includes. The type variables of the
 * superclasses and superinterfaces are bound to the type arguments their subclasses give them; a
 * type argument left a wildcard, a type variable or a generic array is not resolved.</p>
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
final class GenericSignatures {

    private GenericSignatures() {
    }

    /**
     * Resolves the type a class gives a generic type in its generic signature.
     *
     * @param type        The class
     * @param genericType The generic type it implements or extends
     * @return The type with its type arguments, {@code null} when its signature does not give them
     */
    static @Nullable Argument<?> resolve(Class<?> type, Class<?> genericType) {
        Type[] typeArguments = find(type, Map.of(), genericType);
        if (typeArguments == null) {
            return null;
        }
        Argument<?>[] arguments = new Argument<?>[typeArguments.length];
        for (int i = 0; i < typeArguments.length; i++) {
            Argument<?> argument = argument(typeArguments[i]);
            if (argument == null) {
                return null;
            }
            arguments[i] = argument;
        }
        return Argument.of(genericType, arguments);
    }

    // the type arguments of the generic type in the hierarchy of a class, its type variables bound
    private static Type @Nullable [] find(Class<?> type, Map<TypeVariable<?>, Type> bindings, Class<?> genericType) {
        List<Type> superTypes = new ArrayList<>(Arrays.asList(type.getGenericInterfaces()));
        Type superclass = type.getGenericSuperclass();
        if (superclass != null) {
            superTypes.add(superclass);
        }
        for (Type superType : superTypes) {
            Class<?> rawType;
            Type[] typeArguments;
            if (superType instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
                rawType = raw;
                typeArguments = parameterized.getActualTypeArguments().clone();
                for (int i = 0; i < typeArguments.length; i++) {
                    typeArguments[i] = bindings.getOrDefault(typeArguments[i], typeArguments[i]);
                }
            } else if (superType instanceof Class<?> raw) {
                rawType = raw;
                typeArguments = new Type[0];
            } else {
                continue;
            }
            if (rawType == genericType) {
                return typeArguments;
            }
            if (!genericType.isAssignableFrom(rawType)) {
                continue;
            }
            TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
            Map<TypeVariable<?>, Type> superBindings = new HashMap<>();
            for (int i = 0; i < typeParameters.length && i < typeArguments.length; i++) {
                superBindings.put(typeParameters[i], typeArguments[i]);
            }
            Type[] found = find(rawType, superBindings, genericType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // a class or a parameterized type of classes, else null: a wildcard or a type variable left unbound
    private static @Nullable Argument<?> argument(Type type) {
        if (type instanceof Class<?> c) {
            return Argument.of(c);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            Type[] typeArguments = parameterized.getActualTypeArguments();
            Argument<?>[] arguments = new Argument<?>[typeArguments.length];
            for (int i = 0; i < typeArguments.length; i++) {
                Argument<?> argument = argument(typeArguments[i]);
                if (argument == null) {
                    return null;
                }
                arguments[i] = argument;
            }
            return Argument.of(raw, arguments);
        }
        return null;
    }
}
