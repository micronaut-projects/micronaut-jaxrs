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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link Method} of a resource method, which {@code ResourceInfo.getResourceMethod} returns by
 * API contract, without the micronaut-reflection module: the one method the metadata names, by its
 * class, name and parameter types, is looked up; no members are scanned. In a native image, the
 * method needs a reflection config entry.
 */
final class ResourceMethods {

    // the methods of a class, by name and parameter types
    private static final ClassValue<Map<List<Object>, Method>> METHODS = new ClassValue<>() {
        @Override
        protected Map<List<Object>, Method> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private ResourceMethods() {
    }

    /**
     * @param type           The class declaring or inheriting the method
     * @param name           The name of the method
     * @param parameterTypes The parameter types of the method
     * @return The method
     * @throws UnsupportedOperationException When the class has no such method, e.g. in a native
     * image without a reflection config entry for it
     */
    static Method find(Class<?> type, String name, Class<?>... parameterTypes) {
        List<Object> key = List.of(name, List.of(parameterTypes));
        return METHODS.get(type).computeIfAbsent(key, ignored -> lookup(type, name, parameterTypes));
    }

    private static Method lookup(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            try {
                // a public method the class inherits
                return type.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException ex) {
                throw new UnsupportedOperationException("The resource method " + type.getName() + "#" + name
                    + Arrays.toString(parameterTypes) + " cannot be found; in a native image it needs a reflection config entry", ex);
            }
        }
    }
}
