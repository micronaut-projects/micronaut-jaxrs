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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Optional fallback for Jakarta REST client component classes that cannot be created from Micronaut metadata.
 */
@Internal
public interface JaxRsClientComponentInstantiator {

    /**
     * @param componentClass The component class
     * @return Annotation metadata for the component class
     */
    Optional<AnnotationMetadata> annotationMetadata(Class<?> componentClass);

    /**
     * @param componentClass The component class
     * @param contextResolver The resolver for Jakarta REST {@code @Context} injection values
     * @return The instantiated component, or empty if this instantiator cannot handle the class
     */
    Optional<Object> instantiate(Class<?> componentClass, ContextResolver contextResolver);

    /**
     * Resolves client-side Jakarta REST context values.
     */
    @FunctionalInterface
    interface ContextResolver {

        /**
         * @param type The requested context type
         * @return The context value, or {@code null} if unsupported
         */
        @Nullable Object resolve(Class<?> type);
    }
}
