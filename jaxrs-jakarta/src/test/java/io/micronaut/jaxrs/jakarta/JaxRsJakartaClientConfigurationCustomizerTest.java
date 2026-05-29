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
package io.micronaut.jaxrs.jakarta;

import io.micronaut.jaxrs.client.JaxRsClientConfigurationCustomizer;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaxRsJakartaClientConfigurationCustomizerTest {

    @Test
    void customizerLoadsWithoutJsonbOnRuntimeClasspath() {
        AtomicInteger registered = new AtomicInteger();
        boolean found = false;
        for (JaxRsClientConfigurationCustomizer customizer : ServiceLoader.load(JaxRsClientConfigurationCustomizer.class)) {
            if (customizer instanceof JaxRsJakartaClientConfigurationCustomizer) {
                found = true;
                customizer.customize(new JaxRsClientConfigurationCustomizer.ComponentRegistry() {
                    @Override
                    public void register(Object component) {
                        registered.incrementAndGet();
                    }

                    @Override
                    public <T> Optional<ContextResolver<T>> findContextResolver(Class<T> contextType, MediaType mediaType) {
                        return Optional.empty();
                    }
                });
            }
        }

        assertTrue(found);
        assertEquals(0, registered.get());
    }
}
