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

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;

import java.util.Optional;

/**
 * Internal hook used by aggregate compliance modules to register optional
 * client-side Jakarta REST providers without making the default client heavier.
 */
@Internal
public interface JaxRsClientConfigurationCustomizer {

    /**
     * Registers optional components with a standalone Jakarta REST client configuration.
     *
     * @param registry The optional component registry
     */
    void customize(ComponentRegistry registry);

    /**
     * Registry for optional client components.
     */
    @Internal
    interface ComponentRegistry {

        /**
         * Registers an optional provider or feature instance.
         *
         * @param component The component to register
         */
        void register(Object component);

        /**
         * Finds a registered context resolver.
         *
         * @param contextType The context type
         * @param mediaType The media type
         * @param <T> The context type
         * @return The context resolver when one is registered
         */
        <T> Optional<ContextResolver<T>> findContextResolver(Class<T> contextType, MediaType mediaType);
    }
}
