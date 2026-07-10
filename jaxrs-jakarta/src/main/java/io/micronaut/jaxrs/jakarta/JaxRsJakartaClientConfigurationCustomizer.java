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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.jaxrs.client.JaxRsClientConfigurationCustomizer;
import jakarta.json.bind.Jsonb;
import jakarta.ws.rs.ext.ContextResolver;

/**
 * Adds optional Jakarta compliance providers to the JAX-RS client.
 */
@Internal
public final class JaxRsJakartaClientConfigurationCustomizer implements JaxRsClientConfigurationCustomizer {
    private static final String JSONB_CLASS_NAME = "jakarta.json.bind.Jsonb";

    /**
     * Default constructor used by service loading.
     */
    public JaxRsJakartaClientConfigurationCustomizer() {
    }

    @Override
    public void customize(ComponentRegistry registry) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = JaxRsJakartaClientConfigurationCustomizer.class.getClassLoader();
        }
        if (ClassUtils.isPresent(JSONB_CLASS_NAME, classLoader)) {
            JsonbSupport.register(registry);
        }
    }

    private static final class JsonbSupport {

        static void register(ComponentRegistry registry) {
            registry.register(new JaxRsJsonbMessageBodyReaderWriter<>((type, mediaType) -> {
                ContextResolver<Jsonb> resolver = registry.findContextResolver(Jsonb.class, mediaType).orElse(null);
                return resolver == null ? null : resolver.getContext(type);
            }));
        }
    }
}
