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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.Configurable;

import java.util.Map;

/**
 * A simple {@link Configurable} with predefined way to delegate registration.
 *
 * @param <C> The configuration class
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
sealed interface JaxRsConfigurable<C extends Configurable<C>> extends Configurable<C> permits JaxRsClient, JaxRsClientBuilder, JaxRsWebTarget {

    C self();

    @Override
    JaxRsConfiguration getConfiguration();

    @Override
    default C property(String name, Object value) {
        getConfiguration().addProperty(name, value);
        return self();
    }

    @Override
    default C register(Class<?> componentClass) {
        getConfiguration().register(componentClass);
        return self();
    }

    @Override
    default C register(Class<?> componentClass, int priority) {
        getConfiguration().register(componentClass, priority);
        return self();
    }

    @Override
    default C register(Class<?> componentClass, Class<?>... contracts) {
        getConfiguration().register(componentClass, contracts);
        return self();
    }

    @Override
    default C register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        getConfiguration().register(componentClass, contracts);
        return self();
    }

    @Override
    default C register(Object component) {
        getConfiguration().register(component);
        return self();
    }

    @Override
    default C register(Object component, int priority) {
        getConfiguration().register(component, priority);
        return self();
    }

    @Override
    default C register(Object component, Class<?>... contracts) {
        getConfiguration().register(component, contracts);
        return self();
    }

    @Override
    default C register(Object component, Map<Class<?>, Integer> contracts) {
        getConfiguration().register(component, contracts);
        return self();
    }
}
